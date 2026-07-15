"""组合识别：生产优先阿里云，失败回退 YOLO。"""

from __future__ import annotations

import logging

from app.recognition.types import RecognitionOutput

log = logging.getLogger(__name__)


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
        if self._primary.available:
            return getattr(self._primary, "load_error", None)
        return getattr(self._fallback, "load_error", None)

    def recognize(self, session_id: str, video_uri: str | None, device_id: str | None = None,
                  recognition_mode: str | None = None) -> RecognitionOutput:
        if (recognition_mode or "").upper() == "INVENTORY_SNAPSHOT":
            return self._fallback.recognize(
                session_id, video_uri, device_id=device_id, recognition_mode=recognition_mode
            )
        if self._primary.available:
            out = self._primary.recognize(session_id, video_uri)
            if out.items and not out.need_review:
                return out
            log.info("primary recognizer need fallback session=%s", session_id)
        return self._fallback.recognize(session_id, video_uri, device_id=device_id, recognition_mode=recognition_mode)

    def recognize_upload(self, session_id: str, data: bytes, filename: str) -> RecognitionOutput:
        if self._primary.available:
            out = self._primary.recognize_upload(session_id, data, filename)
            if out.items and not out.need_review:
                return out
        return self._fallback.recognize_upload(session_id, data, filename)
