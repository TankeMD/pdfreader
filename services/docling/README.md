# Servicio local Docling

El backend Java envía el PDF como `application/pdf` a `POST /analyze?ocr=AUTO|ALWAYS|OFF`. No hay acceso remoto a documentos ni ejecución de servicios externos. Se aceptan hasta 20 MiB y 20 páginas; hay un único análisis simultáneo. No se aceptan URLs de entrada.

`GET /health` devuelve 503 si faltan modelos o falla su inicialización. Los errores de conversión y las conversiones parciales no se presentan como éxito. El servicio no descarga modelos durante una petición: `prepare_models.py` es un paso explícito de instalación.

La salida normalizada contiene `engine`, `version`, `tables` y `blocks`. Las coordenadas son puntos desde la esquina superior izquierda. Las celdas incluyen spans de filas/columnas y las regiones conservan el orden de lectura de Docling. Java valida cajas, límites y spans antes de consumirlas.

## Instalación y ejecución desde la raíz del repositorio

```bash
bash scripts/setup-docling.sh --dependencies-only  # dependencias y pruebas de contrato/OCR
bash scripts/setup-docling.sh                      # además descarga e inicializa modelos
bash scripts/start-docling.sh                      # loopback, puerto 8090, un proceso
```

Variables: `DOCLING_VENV`, `DOCLING_ARTIFACTS_PATH`, `HF_HOME`, `TESSDATA_PREFIX`. Los valores por defecto usan `/workspace/.tools` y `/workspace/.cache`, sin modificar directorios personales. La instalación exige `uv`, Python 3.12 y Tesseract; añade la configuración TSV y OSD del sistema al directorio de idiomas propio cuando hace falta. Los paquetes están fijados en `requirements.lock`; PyTorch utiliza exclusivamente CPU.

Los modelos oficiales se descargan desde Hugging Face. La política de red debe permitir `huggingface.co` y sus destinos de archivos/redirección (`cas-bridge.xethub.hf.co`, `cas-server.xethub.hf.co`, `cdn-lfs.huggingface.co`, `cdn-lfs-us-1.hf.co`). No desactives TLS ni sustituyas pesos por ficheros de procedencia desconocida.

## Validación

La suite `tests/test_adapter.py` verifica normalización de documentos Docling sintéticos, coordenadas, celdas combinadas, errores HTTP, límite de páginas/tamaño, exclusión mutua y el plugin real Tesseract sobre una imagen de prueba. La inferencia de layout/TableFormer se simula en las pruebas de contrato: pasarlas no acredita que los modelos estén instalados o que una tabla real se reconozca bien.

La validación completa exige HTTP 200 en `/health` y convertir un PDF mediante el backend Java con `engine=HYBRID`. Revisa su `analysis`, tablas, spans, orden de lectura y ausencia de duplicados. En el entorno de preparación actual esa validación está pendiente de desbloquear la descarga de modelos (HTTP 403); el modo `LOCAL` sigue disponible.
