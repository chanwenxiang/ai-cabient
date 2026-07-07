"""识别引擎工厂：yolo | aliyun | hybrid。"""

from __future__ import annotations

import logging
import os

from app.recognition.aliyun_recognizer import AliyunGoodsRecognizer
from app.recognition.hybrid_recognizer import HybridRecognizer
from app.recognition.yolo_recognizer import YoloRecognizer

log = logging.getLogger(__name__)

RECOGNIZER_BACKEND = os.getenv("RECOGNIZER_BACKEND", "yolo").lower().strip()


def create_recognizer():
    yolo = YoloRecognizer()
    if RECOGNIZER_BACKEND == "yolo":
        log.info("recognizer backend=yolo")
        return yolo

    aliyun = AliyunGoodsRecognizer()
    if RECOGNIZER_BACKEND == "aliyun":
        log.info("recognizer backend=aliyun available=%s", aliyun.available)
        return aliyun

    log.info(
        "recognizer backend=hybrid aliyun=%s yolo=%s",
        aliyun.available,
        yolo.available,
    )
    return HybridRecognizer(aliyun, yolo)
