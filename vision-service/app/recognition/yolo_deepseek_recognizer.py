"""YOLO delta 优先，低置信/空结果时 DeepSeek VLM 兜底。"""

from __future__ import annotations

import logging

from app.recognition.types import RecognitionOutput

log = logging.getLogger(__name__)


class YoloDeepSeekRecognizer:
    def __init__(self, yolo, deepseek) -> None:
        self._yolo = yolo
        self._deepseek = deepseek

    @property
    def available(self) -> bool:
        return self._yolo.available or self._deepseek.available

    @property
    def model_path(self) -> str:
        return getattr(self._yolo, "model_path", "yolo")

    @property
    def load_error(self) -> str | None:
        if self._yolo.available:
            return getattr(self._yolo, "load_error", None)
        return getattr(self._deepseek, "load_error", None)

    @property
    def model_version(self) -> str:
        if self._deepseek.available:
            return f"yolo_deepseek+{getattr(self._deepseek, 'model_version', 'deepseek')}"
        return getattr(self._yolo, "model_version", "yolo")

    def recognize(
        self,
        session_id: str,
        video_uri: str | None,
        device_id: str | None = None,
        recognition_mode: str | None = None,
    ) -> RecognitionOutput:
        yolo_out = self._yolo.recognize(
            session_id, video_uri, device_id=device_id, recognition_mode=recognition_mode
        )
        if yolo_out.items and not yolo_out.need_review:
            return yolo_out
        if not self._deepseek.available:
            return yolo_out
        log.info("yolo inconclusive session=%s, try deepseek", session_id)
        ds_out = self._deepseek.recognize(
            session_id, video_uri, device_id=device_id, recognition_mode=recognition_mode
        )
        if ds_out.items:
            return ds_out
        return yolo_out

    def recognize_upload(
        self,
        session_id: str,
        data: bytes,
        filename: str,
        device_id: str | None = None,
    ) -> RecognitionOutput:
        yolo_out = self._yolo.recognize_upload(session_id, data, filename)
        if yolo_out.items and not yolo_out.need_review:
            return yolo_out
        if not self._deepseek.available:
            log.warning(
                "yolo inconclusive upload session=%s but deepseek unavailable: %s",
                session_id,
                getattr(self._deepseek, "load_error", "no key"),
            )
            return yolo_out
        log.info("yolo inconclusive upload session=%s, try deepseek", session_id)
        ds_out = self._deepseek.recognize_upload(session_id, data, filename, device_id=device_id)
        if ds_out.items:
            return ds_out
        return yolo_out
