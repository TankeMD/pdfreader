#!/usr/bin/env bash
# Preparación reproducible: cualquier paso fallido detiene la instalación en lugar de ocultar un entorno incompleto.
set -euo pipefail
# Resuelve la raíz desde este script para poder ejecutarlo desde cualquier directorio.
repository_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
# Permite rutas configurables y mantiene cachés y modelos fuera del código fuente.
export UV_CACHE_DIR=${UV_CACHE_DIR:-/workspace/.cache/uv}
export HF_HOME=${HF_HOME:-/workspace/.cache/huggingface}
export DOCLING_ARTIFACTS_PATH=${DOCLING_ARTIFACTS_PATH:-/workspace/.cache/docling/models}
# Utiliza el almacén de confianza del sistema para descargar a través del proxy sin desactivar TLS.
export SSL_CERT_FILE=${SSL_CERT_FILE:-/etc/ssl/certs/ca-certificates.crt}
export REQUESTS_CA_BUNDLE=${REQUESTS_CA_BUNDLE:-$SSL_CERT_FILE}
export TESSDATA_PREFIX=${TESSDATA_PREFIX:-/workspace/.tools/tessdata}
# El plugin Tesseract de Docling invoca la configuración TSV; se añade solo si no existe para respetar instalaciones previas.
mkdir -p "$TESSDATA_PREFIX/configs"
if [ ! -f "$TESSDATA_PREFIX/configs/tsv" ]; then
  printf 'tessedit_create_tsv 1\n' > "$TESSDATA_PREFIX/configs/tsv"
fi
# Reutiliza el modelo OSD ya instalado por el sistema para que Docling pueda estimar la orientación del texto.
if [ ! -f "$TESSDATA_PREFIX/osd.traineddata" ] && [ -f /usr/share/tesseract-ocr/5/tessdata/osd.traineddata ]; then
  cp /usr/share/tesseract-ocr/5/tessdata/osd.traineddata "$TESSDATA_PREFIX/osd.traineddata"
fi
docling_venv=${DOCLING_VENV:-/workspace/.tools/docling-venv}
# Crea un entorno Python aislado solo si aún no existe.
if [ ! -x "$docling_venv/bin/python" ]; then
  uv venv --python 3.12 "$docling_venv"
fi
# Sincroniza versiones fijadas y selecciona PyTorch CPU para evitar dependencias CUDA innecesarias.
uv pip sync --system-certs --torch-backend cpu --python "$docling_venv/bin/python" "$repository_root/services/docling/requirements.lock"
cd "$repository_root/services/docling"
# Las pruebas de contrato y OCR se ejecutan incluso cuando se omite la descarga de pesos neuronales.
"$docling_venv/bin/python" -m unittest discover -s tests -v
# El modo de dependencias no acredita inferencia. La instalación completa descarga e inicializa los modelos y falla si no puede hacerlo.
if [ "${1:-}" != "--dependencies-only" ]; then
  "$docling_venv/bin/python" prepare_models.py
  "$docling_venv/bin/python" -c 'from app import converter; converter(False); print("Docling model initialization: OK")'
fi
