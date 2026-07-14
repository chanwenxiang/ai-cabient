"""组合识别：多识别后端 fallback（已废弃，仅保留引用兼容）。"""

from __future__ import annotations

import logging
import os

from app.recognition.frame_extract import is_video_path
from app.recognition.types import RecognitionOutput
from app.storage import resolve_video_path

log = logging.getLogger(__name__)

HYBRID_DELTA_FIRST = os.getenv("HYBRID_DELTA_FIRST", "true").lower() == "true"


class HybridRecognizer:
    def __init__(self, primary, fallback) -> None:
        self._primary = primary
        self._fallback = fallback

    @property
    def available(self) -> bool:
        return self._primary.available or self._fallback.available

    @property
    def model_path(self) -> str:
        return getattr(self._primary, "model_path", "hybrid")

    @property
    def load_error(self) -> str | None:
        if self._fallback.available:
            return getattr(self._fallback, "load_error", None)
        return getattr(self._primary, "load_error", None)

    @property
    def model_version(self) -> str:
    @property
    def model_version(self) -> str:
        return "hybrid-v1"

    def recognize(self, session_id: str, video_uri: str | None, device_id: str | None = None,
                  recognition_mode: str | None = None) -> RecognitionOutput:
        if (recognition_mode or "").upper() == "INVENTORY_SNAPSHOT":
            return self._fallback.recognize(
                session_id, video_uri, device_id=device_id, recognition_mode=recognition_mode
            )

        # 购物视频：本地 Retail-OS + delta 优先（开关 HYBRID_DELTA_FIRST）
        if HYBRID_DELTA_FIRST and self._should_delta_first(video_uri):
            yolo_out = self._fallback.recognize(
                session_id, video_uri, device_id=device_id, recognition_mode=recognition_mode
            )
            if yolo_out.items and not yolo_out.need_review:
                return yolo_out
            log.info("yolo delta inconclusive session=%s", session_id)

        if self._primary.available:
            out = self._primary.recognize(session_id, video_uri)
            if out.items and not out.need_review:
                return out
            log.info("primary recognizer need fallback session=%s", session_id)
        return self._fallback.recognize(session_id, video_uri, device_id=device_id, recognition_mode=recognition_mode)

    @staticmethod
    def _should_delta_first(video_uri: str | None) -> bool:
        if not video_uri:
            return False
        local = resolve_video_path(video_uri)
        return bool(local and os.path.exists(local) and is_video_path(local))

    def recognize_upload(self, session_id: str, data: bytes, filename: str) -> RecognitionOutput:
        if self._primary.available:
            out = self._primary.recognize_upload(session_id, data, filename)
            if out.items and not out.need_review:
                return out
        return self._fallback.recognize_upload(session_id, data, filename)

