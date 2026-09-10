"""mock 识别器单元测试。"""

from app.recognition.mock_recognizer import (
    MockRecognizer,
    safe_session_file_stem,
    set_force_need_review,
)
from app.recognition.quectel_recognizer import QuectelRecognizer
from app.storage import resolve_video_path


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


def test_safe_session_file_stem_blocks_traversal():
    assert ".." not in safe_session_file_stem("../etc/passwd")
    assert "/" not in safe_session_file_stem("a/../../b")
    assert safe_session_file_stem("SESS-001") == "SESS-001"


def test_file_uri_outside_cache_rejected(tmp_path, monkeypatch):
    cache = tmp_path / "videos"
    cache.mkdir()
    import app.storage as storage

    monkeypatch.setattr(storage, "VIDEO_CACHE_DIR", str(cache))
    outside = tmp_path / "secret.txt"
    outside.write_text("x", encoding="utf-8")
    assert storage.resolve_video_path(f"file://{outside}") is None
    inside = cache / "clip.mp4"
    inside.write_bytes(b"data")
    assert storage.resolve_video_path(f"file://{inside}") == str(inside.resolve())


def test_quectel_stub_returns_need_review_not_raise():
    rec = QuectelRecognizer()
    out = rec.recognize("S-Q", None, recognition_mode="NORMAL")
    assert out.need_review is True
    assert out.items == []
