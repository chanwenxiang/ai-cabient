"""识别引擎工厂：mock（开发联调）| quectel（端侧提供方占位）。"""

from __future__ import annotations

import logging
import os

from app.recognition.mock_recognizer import MockRecognizer
from app.recognition.quectel_recognizer import QuectelRecognizer

log = logging.getLogger(__name__)

RECOGNIZER_BACKEND = os.getenv("RECOGNIZER_BACKEND", "mock").lower().strip()
_DEPRECATED_BACKENDS = frozenset({"yolo", "yolo_deepseek", "hybrid"})


def create_recognizer():
    if RECOGNIZER_BACKEND == "quectel":
        return QuectelRecognizer()
    if RECOGNIZER_BACKEND in _DEPRECATED_BACKENDS:
        log.warning(
            "RECOGNIZER_BACKEND=%s is removed; using mock (edge recognition is external)",
            RECOGNIZER_BACKEND,
        )
    log.info("recognizer backend=mock (production uses edge provider results)")
    return MockRecognizer()
