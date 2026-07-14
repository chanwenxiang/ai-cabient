"""Model path / version helpers for vision-service."""

from __future__ import annotations

import os
from pathlib import Path

SERVICE_ROOT = Path(__file__).resolve().parent.parent.parent


def resolve_model_path() -> str:
    raw = os.getenv("YOLO_MODEL_PATH", "models/yolov8n.pt")
    path = Path(raw)
    if not path.is_absolute():
        path = SERVICE_ROOT / path
    return str(path)


def resolve_model_version(model_path: str) -> str:
    explicit = (os.getenv("YOLO_MODEL_VERSION") or os.getenv("MODEL_VERSION") or "").strip()
    if explicit:
        return explicit
    name = Path(model_path).name
    if name.endswith(".pt"):
        return name[:-3]
    return name or "unknown"


def is_generic_coco_model(model_path: str, model_version: str) -> bool:
    haystack = f"{model_path} {model_version}".lower()
    return "yolov8n" in haystack or haystack.endswith("/yolov8n")
