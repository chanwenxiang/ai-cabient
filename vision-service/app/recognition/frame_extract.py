"""从视频抽取开门/关门关键帧。"""

from __future__ import annotations

import logging
from pathlib import Path

from app.storage import VIDEO_CACHE_DIR

log = logging.getLogger(__name__)

VIDEO_EXTENSIONS = {".mp4", ".avi", ".mov", ".mkv", ".webm", ".m4v"}


def is_video_path(path: str) -> bool:
    return Path(path).suffix.lower() in VIDEO_EXTENSIONS


def extract_key_frames(local_path: str) -> dict[str, str | None]:
    """返回 open/mid/close 帧路径；非视频或失败时均为 None。"""
    empty = {"open": None, "mid": None, "close": None}
    if not is_video_path(local_path):
        return empty

    try:
        import cv2  # type: ignore
    except ImportError:
        log.warning("opencv not installed, skip frame extract path=%s", local_path)
        return empty

    cap = cv2.VideoCapture(local_path)
    if not cap.isOpened():
        log.warning("cannot open video path=%s", local_path)
        return empty

    frame_count = max(int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0), 1)
    targets = {
        "open": 0,
        "mid": max(frame_count // 2, 0),
        "close": max(frame_count - 1, 0),
    }
    if frame_count > 20:
        targets["open"] = max(int(frame_count * 0.05), 0)
        targets["close"] = min(int(frame_count * 0.95), frame_count - 1)

    frame_dir = Path(VIDEO_CACHE_DIR) / "frames"
    frame_dir.mkdir(parents=True, exist_ok=True)
    stem = Path(local_path).stem
    out: dict[str, str | None] = {"open": None, "mid": None, "close": None}

    for label, idx in targets.items():
        cap.set(cv2.CAP_PROP_POS_FRAMES, idx)
        ok, frame = cap.read()
        if not ok or frame is None:
            log.warning("frame extract failed path=%s label=%s idx=%s", local_path, label, idx)
            continue
        path = frame_dir / f"{stem}_{label}.jpg"
        cv2.imwrite(str(path), frame)
        out[label] = str(path)
        log.info("extracted %s frame video=%s idx=%s -> %s", label, local_path, idx, path)

    cap.release()
    return out
