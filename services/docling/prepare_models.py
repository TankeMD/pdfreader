"""Instala explícitamente los modelos oficiales; la inferencia posterior usa los pesos locales sin descargarlos."""
import os
from pathlib import Path

# Solo el instalador habilita descargas; el proceso de servicio vuelve a arrancar en modo offline.
os.environ["HF_HUB_OFFLINE"] = "0"
os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")

from docling.datamodel.pipeline_options import LayoutOptions
from docling.models.stages.table_structure.table_structure_model import TableStructureModel
from docling.models.utils.hf_model_download import download_hf_model

# Se guardan los pesos fuera del repositorio, en una ubicación que conserva la instantánea del entorno.
destination = Path(os.environ.get("DOCLING_ARTIFACTS_PATH", "/workspace/.cache/docling/models"))
destination.mkdir(parents=True, exist_ok=True)
# Se utiliza la especificación oficial de layout de la versión instalada, sin sustituir modelos por fuentes no verificadas.
layout = LayoutOptions().model_spec
download_hf_model(repo_id=layout.repo_id, revision=layout.revision,
                  local_dir=destination / layout.repo_id.replace("/", "--"))
# Los artefactos TableFormer incluyen la variante Accurate configurada en el servicio.
TableStructureModel.download_models(local_dir=destination / TableStructureModel._model_repo_folder)
print("Layout and TableFormer models installed.")
