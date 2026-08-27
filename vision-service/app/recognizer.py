"""识别模块入口（兼容旧 import 路径）。"""

from app.recognition.factory import create_recognizer
from app.recognition.mock_recognizer import MockRecognizer, get_force_need_review, set_force_need_review
from app.recognition.types import RecognizedItem, RecognitionOutput

__all__ = [
    "MockRecognizer",
    "RecognizedItem",
    "RecognitionOutput",
    "create_recognizer",
    "get_force_need_review",
    "get_recognizer",
    "set_force_need_review",
]

_recognizer = None


def get_recognizer():
    global _recognizer
    if _recognizer is None:
        _recognizer = create_recognizer()
    return _recognizer
