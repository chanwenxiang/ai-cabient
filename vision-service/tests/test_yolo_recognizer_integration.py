"""YOLO 识别器集成测试（mock 模型推理）。"""

from __future__ import annotations

from unittest.mock import MagicMock, patch

from app.recognition.yolo_recognizer import YoloRecognizer


@patch.dict("os.environ", {"VISION_FORCE_REAL": "true", "MOCK_ENABLED": "true", "YOLO_RECOGNITION_MODE": "delta"})
@patch("app.recognition.yolo_recognizer.extract_key_frames")
@patch("app.recognition.yolo_recognizer.resolve_video_path")
def test_delta_recognize_taken_one(mock_resolve, mock_frames):
    mock_resolve.return_value = "/tmp/fake.mp4"
    mock_frames.return_value = {"open": "/tmp/open.jpg", "close": "/tmp/close.jpg", "mid": "/tmp/mid.jpg"}

    recognizer = YoloRecognizer.__new__(YoloRecognizer)
    recognizer._model = MagicMock()
    recognizer.model_version = "yolov8n"
    recognizer.recognition_mode = "delta"
    recognizer.load_error = None

    with patch.object(
        recognizer,
        "_count_skus",
        side_effect=[
            ({"SKU-DEMO-001": (2, 0.9)}, ["bottle"]),
            ({"SKU-DEMO-001": (1, 0.88)}, ["bottle"]),
        ],
    ):
        out = recognizer._infer_delta("/tmp/fake.mp4")

    assert out.items[0].sku_id == "SKU-DEMO-001"
    assert out.items[0].quantity == 1
    assert "delta" in out.model_version
    assert out.model_version != "mock-v1"


@patch.dict("os.environ", {"VISION_FORCE_REAL": "true", "MOCK_ENABLED": "true"})
def test_failure_returns_review_not_mock():
    recognizer = YoloRecognizer.__new__(YoloRecognizer)
    recognizer._model = None
    recognizer.model_version = "yolov8n"
    recognizer.recognition_mode = "delta"
    recognizer.load_error = "missing"

    out = recognizer._on_failure("S1", "minio://x", "model-unavailable")
    assert out.items == []
    assert out.need_review is True
    assert "mock" not in out.model_version
