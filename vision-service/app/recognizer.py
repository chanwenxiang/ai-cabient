"""识别模块入口（兼容旧 import 路径）。"""

from app.recognition.factory import create_recognizer
from app.recognition.types import RecognizedItem, RecognitionOutput
from app.recognition.yolo_recognizer import YoloRecognizer

__all__ = ["RecognizedItem", "RecognitionOutput", "YoloRecognizer", "create_recognizer", "get_recognizer"]

_recognizer = None


def get_recognizer():
    global _recognizer
    if _recognizer is None:
        _recognizer = create_recognizer()
    return _recognizer
