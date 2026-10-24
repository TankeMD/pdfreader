"""Pruebas de contrato con documentos sintéticos y OCR real; no ejecutan inferencia neuronal de layout ni de tablas."""
import io
import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import pypdfium2 as pdfium
from docling_core.types.doc import (BoundingBox, CoordOrigin, DocItemLabel, DoclingDocument,
                                   ProvenanceItem, TableCell, TableData)
from docling_core.types.doc.base import Size
from fastapi.testclient import TestClient

import app as adapter


def pdf_bytes(pages=1):
    """Genera un PDF sintético en memoria para probar validaciones de transporte y límites sin datos de usuarios."""
    with pdfium.PdfDocument.new() as doc:
        for _ in range(pages):
            doc.new_page(300, 400).close()
        data = io.BytesIO()
        doc.save(data)
        return data.getvalue()


def document():
    """Construye un documento Docling sintético con una celda combinada; no ejecuta modelos de layout ni de tablas."""
    doc = DoclingDocument(name="synthetic")
    doc.add_page(page_no=1, size=Size(width=300, height=400))
    table_box = BoundingBox(l=10, t=380, r=290, b=280, coord_origin=CoordOrigin.BOTTOMLEFT)
    # La cabecera ocupa dos columnas; la normalización debe conservar un único objeto con columnSpan=2.
    merged = TableCell(row_span=1, col_span=2, start_row_offset_idx=0, end_row_offset_idx=1,
                       start_col_offset_idx=0, end_col_offset_idx=2, text="Header",
                       bbox=BoundingBox(l=10, t=380, r=290, b=340, coord_origin=CoordOrigin.BOTTOMLEFT))
    cells = [merged]
    for col, text in enumerate(["Item", "10"]):
        cells.append(TableCell(row_span=1, col_span=1, start_row_offset_idx=1, end_row_offset_idx=2,
                     start_col_offset_idx=col, end_col_offset_idx=col + 1, text=text,
                     bbox=BoundingBox(l=10 + col * 140, t=340, r=150 + col * 140, b=280,
                                      coord_origin=CoordOrigin.BOTTOMLEFT)))
    doc.add_table(data=TableData(num_rows=2, num_cols=2, table_cells=cells),
                  prov=ProvenanceItem(page_no=1, bbox=table_box, charspan=(0, 0)))
    doc.add_text(label=DocItemLabel.TEXT, text="A paragraph", prov=ProvenanceItem(page_no=1,
                 bbox=BoundingBox(l=10, t=260, r=200, b=240, coord_origin=CoordOrigin.BOTTOMLEFT), charspan=(0, 11)))
    return doc


class AdapterTests(unittest.TestCase):
    """Pruebas del adaptador y del plugin OCR real; las respuestas de inferencia neuronal se simulan explícitamente."""
    def setUp(self):
        """Crea un cliente ASGI para ejecutar peticiones sobre la aplicación sin iniciar un servidor externo."""
        self.client = TestClient(adapter.app)

    def post(self, content, suffix=""):
        """Envía contenido como PDF al endpoint y permite añadir opciones de consulta al caso de prueba."""
        return self.client.post("/analyze" + suffix, content=content, headers={"Content-Type": "application/pdf"})

    def test_normalizes_coordinates_and_merged_cells(self):
        """Comprueba conversión de coordenadas y spans a partir de un documento Docling construido en memoria."""
        result = adapter.normalize(document())
        self.assertEqual(result["tables"][0]["bounds"], {"x": 10, "y": 20, "width": 280, "height": 100})
        self.assertEqual(result["tables"][0]["cells"][0]["columnSpan"], 2)
        self.assertEqual(result["blocks"][0]["text"], "A paragraph")

    def test_real_docling_tesseract_plugin_reads_tsv_without_layout_models(self):
        """Ejecuta el plugin Tesseract real de Docling sobre una imagen sintética sin cargar layout ni TableFormer."""
        from PIL import Image, ImageDraw, ImageFont
        from docling.datamodel.accelerator_options import AcceleratorOptions
        from docling.datamodel.pipeline_options import TesseractCliOcrOptions
        from docling.models.stages.ocr.tesseract_ocr_cli_model import TesseractOcrCliModel
        # Aquí sí se ejecuta Tesseract a través de Docling, pero no se requieren pesos neuronales de layout/TableFormer.
        model = TesseractOcrCliModel(enabled=True, artifacts_path=None,
                  options=TesseractCliOcrOptions(lang=["spa", "eng"], path=os.environ.get("TESSDATA_PREFIX")),
                  accelerator_options=AcceleratorOptions())
        with tempfile.TemporaryDirectory() as directory:
            image = Image.new("RGB", (800, 150), "white")
            ImageDraw.Draw(image).text((25, 40), "INVOICE TEXT SAMPLE", font=ImageFont.load_default(size=40), fill="black")
            file = Path(directory) / "page.png"
            image.save(file)
            result = model._run_tesseract(str(file), None)
        self.assertIn("INVOICE", " ".join(result["text"].dropna()))

    def test_models_missing_is_not_ready_or_successful_conversion(self):
        """Exige HTTP 503 tanto en salud como en conversión cuando faltan los pesos locales."""
        # El directorio vacío fuerza ausencia de modelos independientemente de los que haya instalados en la máquina.
        with tempfile.TemporaryDirectory() as path, patch.object(adapter, "MODELS", Path(path)):
            adapter.converter.cache_clear()
            self.assertEqual(self.client.get("/health").status_code, 503)
            result = self.post(pdf_bytes())
            self.assertEqual(result.status_code, 503)
            self.assertEqual(result.json()["detail"]["code"], "MODELS_MISSING")

    def test_request_transport_and_normalization_with_stubbed_inference(self):
        """Prueba el contrato HTTP con un convertidor simulado; su éxito no acredita inferencia neuronal."""
        # Este doble simula exclusivamente inferencia; el transporte y la normalización siguen ejecutándose realmente.
        fake = SimpleNamespace(convert=lambda *args, **kwargs: SimpleNamespace(status=SimpleNamespace(value="success"), document=document()))
        with patch.object(adapter, "converter", return_value=fake):
            result = self.post(pdf_bytes(), "?ocr=OFF")
        self.assertEqual(result.status_code, 200)
        self.assertEqual(result.json()["tables"][0]["columns"], 2)

    def test_rejects_invalid_pdf_mode_empty_body_and_content_type(self):
        """Comprueba errores diferenciados para bytes inválidos, carga vacía, modo desconocido y tipo MIME incorrecto."""
        self.assertEqual(self.post(b"bad").status_code, 422)
        self.assertEqual(self.post(b"").status_code, 400)
        self.assertEqual(self.post(b"bad", "?ocr=WRONG").status_code, 400)
        self.assertEqual(self.client.post("/analyze", content=b"bad").status_code, 415)

    def test_rejects_oversized_and_too_many_pages(self):
        """Verifica límites de bytes y páginas antes de permitir la conversión del documento."""
        with patch.object(adapter, "MAX_BYTES", 8):
            self.assertEqual(self.post(b"0123456789").status_code, 413)
        self.assertEqual(self.post(pdf_bytes(21)).status_code, 422)

    def test_busy_and_partial_conversion_are_explicit_errors(self):
        """Comprueba que la exclusión mutua y las conversiones parciales producen errores, no resultados aparentemente válidos."""
        with adapter.GATE:
            self.assertEqual(self.post(pdf_bytes()).status_code, 429)
        fake = SimpleNamespace(convert=lambda *args, **kwargs: SimpleNamespace(status=SimpleNamespace(value="partial_success")))
        with patch.object(adapter, "converter", return_value=fake):
            self.assertEqual(self.post(pdf_bytes()).status_code, 422)


if __name__ == "__main__":
    unittest.main()
