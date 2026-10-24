"""Análisis semántico local: los bytes del PDF no se envían a servicios de inferencia externos."""
from __future__ import annotations

import io
import os
import threading
from functools import lru_cache
from importlib.metadata import version
from pathlib import Path

# El arranque favorece pesos locales y desactiva telemetría antes de importar las librerías de modelos.
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")

import pypdfium2 as pdfium
from docling.datamodel.accelerator_options import AcceleratorDevice, AcceleratorOptions
from docling.datamodel.base_models import DocumentStream, InputFormat
from docling.datamodel.pipeline_options import PdfPipelineOptions, TableFormerMode, TesseractCliOcrOptions
from docling.document_converter import DocumentConverter, PdfFormatOption
from docling_core.types.doc import TableItem, TextItem
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool

MAX_BYTES = 20 * 1024 * 1024
MODELS = Path(os.environ.get("DOCLING_ARTIFACTS_PATH", "/workspace/.cache/docling/models"))
# Un único análisis por proceso acota memoria y evita inferencias simultáneas sobre convertidores compartidos.
GATE = threading.Lock()
app = FastAPI(title="PDF Reader local Docling adapter", docs_url=None, redoc_url=None)


def models_present() -> bool:
    """Comprueba la presencia mínima de pesos locales; la salud valida además que puedan inicializarse."""
    layout = MODELS / "docling-project--docling-layout-heron"
    table = MODELS / "docling-project--docling-models/model_artifacts/tableformer/accurate"
    return (layout.is_dir() and any(layout.glob("*.safetensors"))
            and table.is_dir() and any(table.glob("*.safetensors")))


# Se conservan como máximo las dos variantes: con OCR y sin OCR. Cada una inicializa su propio pipeline.
@lru_cache(maxsize=2)
def converter(with_ocr: bool) -> DocumentConverter:
    """Construye y cachea las variantes con/sin OCR, usando CPU y sin servicios remotos. Falla si no hay modelos."""
    if not models_present():
        raise HTTPException(503, detail={"code": "MODELS_MISSING", "message": "Run prepare_models.py after enabling model downloads."})
    # La ruta de pesos es explícita y la inferencia remota queda deshabilitada; el coste se limita a 180 segundos y dos hilos CPU.
    options = PdfPipelineOptions(artifacts_path=MODELS, do_ocr=with_ocr, do_table_structure=True,
                                 document_timeout=180, enable_remote_services=False)
    options.accelerator_options = AcceleratorOptions(device=AcceleratorDevice.CPU, num_threads=2)
    options.ocr_options = TesseractCliOcrOptions(lang=["spa", "eng"], path=os.environ.get("TESSDATA_PREFIX"))
    options.table_structure_options.mode = TableFormerMode.ACCURATE
    result = DocumentConverter(allowed_formats=[InputFormat.PDF], format_options={
        InputFormat.PDF: PdfFormatOption(pipeline_options=options)
    })
    # Inicializar aquí permite detectar pesos corruptos o dependencias ausentes antes de informar de disponibilidad.
    result.initialize_pipeline(InputFormat.PDF)
    return result


def box(bounds, height: float) -> dict:
    """Convierte una caja Docling al origen superior izquierdo en puntos, usando la altura de su página."""
    b = bounds.to_top_left_origin(page_height=height)
    return {"x": b.l, "y": b.t, "width": b.r - b.l, "height": b.b - b.t}


def normalize(document) -> dict:
    """Adapta tablas y bloques Docling al contrato Java conservando spans, procedencia y orden de lectura."""
    tables, blocks = [], []
    # El recorrido entrega orden semántico, no solo coordenadas. Los elementos sin procedencia no pueden asociarse a una página.
    for index, (item, _) in enumerate(document.iterate_items()):
        if not item.prov:
            continue
        provenance = item.prov[0]
        page = provenance.page_no
        height = document.pages[page].size.height
        bounds = box(provenance.bbox, height)
        if isinstance(item, TableItem):
            cells = []
            # Los índices y spans se transmiten sin expandir celdas combinadas; Java comprueba después la topología y los límites.
            for cell in item.data.table_cells:
                cells.append({"row": cell.start_row_offset_idx, "column": cell.start_col_offset_idx,
                              "rowSpan": cell.row_span, "columnSpan": cell.col_span,
                              "text": cell.text, "bounds": box(cell.bbox, height) if cell.bbox else None})
            tables.append({"id": item.self_ref, "page": page, "order": index, "bounds": bounds,
                           "rows": item.data.num_rows, "columns": item.data.num_cols, "cells": cells})
        elif isinstance(item, TextItem):
            blocks.append({"id": item.self_ref, "page": page, "order": index,
                           "bounds": bounds, "label": item.label.value, "text": item.text})
    return {"engine": "docling", "version": version("docling"), "tables": tables, "blocks": blocks}


def analyze_bytes(data: bytes, ocr: str) -> dict:
    """Valida el PDF, selecciona OCR según texto nativo y convierte en memoria; no acepta conversiones parciales."""
    try:
        # La inspección previa usa texto seleccionable para evitar OCR innecesario sobre facturas y logos.
        with pdfium.PdfDocument(data) as pdf:
            if not 1 <= len(pdf) <= 20:
                raise HTTPException(422, detail={"code": "PAGE_LIMIT"})
            needs_ocr = False
            # Una página con menos de 20 caracteres activa la hipótesis OCR de AUTO para este documento; es un umbral heurístico.
            for page in pdf:
                text = page.get_textpage()
                try:
                    if len(text.get_text_range().strip()) < 20:
                        needs_ocr = True
                finally:
                    # Se liberan explícitamente los manejadores nativos del texto y de la página, también si falla la lectura.
                    text.close()
                    page.close()
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(422, detail={"code": "INVALID_PDF"}) from exc
    with_ocr = ocr == "ALWAYS" or ocr == "AUTO" and needs_ocr
    try:
        converted = converter(with_ocr).convert(DocumentStream(name="input.pdf", stream=io.BytesIO(data)),
                                                 max_num_pages=20, max_file_size=MAX_BYTES)
        # Una conversión parcial puede omitir contenido: se rechaza en vez de devolver una plantilla incompleta como satisfactoria.
        if converted.status.value != "success":
            raise HTTPException(422, detail={"code": "INCOMPLETE_CONVERSION"})
        return normalize(converted.document)
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(503, detail={"code": "DOCLING_FAILED", "message": "Local inference failed; check model installation and service logs."}) from exc


@app.get("/health")
def health():
    """Comprueba pesos e inicialización real de la variante sin OCR; un puerto abierto no equivale a disponibilidad."""
    if not models_present():
        return JSONResponse(status_code=503, content={"status": "MODELS_MISSING", "ready": False})
    try:
        converter(False)
    except Exception:
        return JSONResponse(status_code=503, content={"status": "INITIALIZATION_FAILED", "ready": False})
    return {"status": "UP", "ready": True, "version": version("docling")}


@app.post("/analyze")
async def analyze(request: Request, ocr: str = "AUTO"):
    """Recibe bytes PDF con tamaño acotado, exclusión mutua y validación de opciones; libera siempre el bloqueo."""
    if ocr not in {"AUTO", "ALWAYS", "OFF"}:
        raise HTTPException(400, detail={"code": "INVALID_OCR_MODE"})
    if request.headers.get("content-type", "").split(";")[0] != "application/pdf":
        raise HTTPException(415, detail={"code": "PDF_REQUIRED"})
    if not GATE.acquire(blocking=False):
        raise HTTPException(429, detail={"code": "BUSY"})
    try:
        # La carga se controla mientras llega, sin confiar únicamente en Content-Length ni aceptar URLs de entrada.
        data = bytearray()
        async for chunk in request.stream():
            data.extend(chunk)
            if len(data) > MAX_BYTES:
                raise HTTPException(413, detail={"code": "FILE_TOO_LARGE"})
        if not data:
            raise HTTPException(400, detail={"code": "EMPTY_FILE"})
        # La inferencia bloqueante se ejecuta fuera del bucle ASGI; el bloqueo de documento permanece adquirido hasta que termina.
        return await run_in_threadpool(analyze_bytes, bytes(data), ocr)
    finally:
        # El finally devuelve la plaza tanto ante éxito como ante PDF inválido, carga excesiva o fallo de los modelos.
        GATE.release()
