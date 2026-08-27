"""mock 识别器单元测试。"""

import os

from app.recognition.mock_recognizer import MockRecognizer, set_force_need_review


def test_mock_recognize_defaults_need_review(monkeypatch):
    monkeypatch.setenv("MOCK_ENABLED", "true")
    monkeypatch.setenv("VISION_FORCE_REAL", "false")
    rec = MockRecognizer()
    out = rec.recognize("S1", "minio://videos/test.mp4", device_id="CAB-001")
    assert out.need_review is True
    assert out.model_version == "mock-v1"
    assert len(out.items) == 1


def test_force_need_review_toggle():
    set_force_need_review(True)
    rec = MockRecognizer()
    out = rec.recognize("S2", None)
    assert out.need_review is True
