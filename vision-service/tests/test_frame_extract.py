"""frame_extract 单测（V-P2-5）；无 opencv 时仍覆盖分支。"""

from pathlib import Path

from app.recognition.frame_extract import extract_key_frames, is_video_path


def test_is_video_path():
    assert is_video_path("a.mp4") is True
    assert is_video_path("a.MOV") is True
    assert is_video_path("a.jpg") is False
    assert is_video_path("a.txt") is False


def test_extract_key_frames_non_video_returns_empty():
    out = extract_key_frames("not-a-video.jpg")
    assert out == {"open": None, "mid": None, "close": None}


def test_extract_key_frames_without_opencv(monkeypatch):
    import builtins
    import app.recognition.frame_extract as fe

    real_import = builtins.__import__

    def guarded(name, globals=None, locals=None, fromlist=(), level=0):
        if name == "cv2" or (isinstance(name, str) and name.startswith("cv2.")):
            raise ImportError("no cv2")
        return real_import(name, globals, locals, fromlist, level)

    monkeypatch.setattr(builtins, "__import__", guarded)
    out = fe.extract_key_frames("clip.mp4")
    assert out == {"open": None, "mid": None, "close": None}


def test_extract_key_frames_with_fake_cv2(tmp_path, monkeypatch):
    import app.recognition.frame_extract as fe
    import types
    import sys

    monkeypatch.setattr(fe, "VIDEO_CACHE_DIR", str(tmp_path))
    video = tmp_path / "sess.mp4"
    video.write_bytes(b"fake")

    class FakeCap:
        def __init__(self, _path):
            self._i = 0

        def isOpened(self):
            return True

        def get(self, _prop):
            return 100

        def set(self, _prop, _idx):
            return True

        def read(self):
            self._i += 1
            return True, object()

        def release(self):
            return None

    fake_cv2 = types.SimpleNamespace(
        VideoCapture=FakeCap,
        CAP_PROP_FRAME_COUNT=7,
        CAP_PROP_POS_FRAMES=1,
        imwrite=lambda path, _frame: Path(path).write_bytes(b"jpg") or True,
    )
    monkeypatch.setitem(sys.modules, "cv2", fake_cv2)
    out = fe.extract_key_frames(str(video))
    assert out["open"] and Path(out["open"]).is_file()
    assert out["mid"] and Path(out["mid"]).is_file()
    assert out["close"] and Path(out["close"]).is_file()
