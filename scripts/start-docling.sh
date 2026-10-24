#!/usr/bin/env bash
# Arranca únicamente el servicio local; la instalación y descarga de modelos son pasos separados.
set -euo pipefail
# Encuentra el módulo Python independientemente del directorio desde el que se invoque el script.
repository_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
# Usa las mismas ubicaciones de caché y artefactos que el instalador.
export HF_HOME=${HF_HOME:-/workspace/.cache/huggingface}
export DOCLING_ARTIFACTS_PATH=${DOCLING_ARTIFACTS_PATH:-/workspace/.cache/docling/models}
# Durante el servicio no se descargan pesos ni se activa telemetría.
export HF_HUB_OFFLINE=1
export HF_HUB_DISABLE_TELEMETRY=1
export TESSDATA_PREFIX=${TESSDATA_PREFIX:-/workspace/.tools/tessdata}
docling_venv=${DOCLING_VENV:-/workspace/.tools/docling-venv}
cd "$repository_root/services/docling"
# Loopback evita exponer el analizador fuera de la máquina; un worker mantiene la exclusión mutua por instancia.
exec "$docling_venv/bin/python" -m uvicorn app:app --host 127.0.0.1 --port 8090 --workers 1
