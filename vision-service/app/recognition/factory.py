"""识别引擎工厂：yolo | yolo_deepseek（YOLO delta + DeepSeek 兜底）。"""

from __future__ import annotations

import logging
import os

from app.recognition.deepseek_recognizer import DeepSeekRecognizer
from app.recognition.quectel_recognizer import QuectelRecognizer
from app.recognition.yolo_deepseek_recognizer import YoloDeepSeekRecognizer
from app.recognition.yolo_recognizer import YoloRecognizer

log = logging.getLogger(__name__)

RECOGNIZER_BACKEND = os.getenv("RECOGNIZER_BACKEND", "yolo").lower().strip()
_DEPRECATED_BACKENDS = frozenset({"hybrid"})


def create_recognizer():
    if RECOGNIZER_BACKEND == "quectel":
        # 移远端侧识别：端侧结果直通平台；此处保留云端占位，SDK 就绪前不可用
        return QuectelRecognizer()
    yolo = YoloRecognizer()
    if RECOGNIZER_BACKEND in _DEPRECATED_BACKENDS:
        log.warning(
            "RECOGNIZER_BACKEND=%s is deprecated; using yolo",
            RECOGNIZER_BACKEND,
        )
        return yolo
    if RECOGNIZER_BACKEND == "yolo_deepseek":
        deepseek = DeepSeekRecognizer()
        log.info(
            "recognizer backend=yolo_deepseek yolo=%s deepseek=%s",
            yolo.available,
            deepseek.available,
        )
        return YoloDeepSeekRecognizer(yolo, deepseek)
    log.info("recognizer backend=yolo mode=%s", os.getenv("YOLO_RECOGNITION_MODE", "delta"))
    return yolo

