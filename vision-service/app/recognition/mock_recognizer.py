"""开发/联调 mock 识别器（无自研 YOLO；生产由端侧识别提供方回传结果）。"""

from __future__ import annotations

import logging
import os
from pathlib import Path

from app.recognition.mapping_client import fetch_default_sku, fetch_inventory_snapshot
from app.recognition.types import RecognizedItem, RecognitionOutput
from app.storage import VIDEO_CACHE_DIR

log = logging.getLogger(__name__)

MOCK_SKU = os.getenv("MOCK_SKU_ID", "SKU-DEMO-001")
MOCK_ENABLED = os.getenv("MOCK_ENABLED", "true").lower() == "true"
VISION_FORCE_REAL = os.getenv("VISION_FORCE_REAL", "false").lower() == "true"
_force_need_review = os.getenv("MOCK_FORCE_NEED_REVIEW", "false").lower() == "true"


def get_force_need_review() -> bool:
    return _force_need_review


def set_force_need_review(enabled: bool) -> bool:
    global _force_need_review
    _force_need_review = bool(enabled)
    log.info("MOCK_FORCE_NEED_REVIEW set to %s", _force_need_review)
    return _force_need_review


def use_real_inference() -> bool:
    """真实识别应由端侧提供方完成；云端 mock 服务不承载生产推理。"""
    return VISION_FORCE_REAL or not MOCK_ENABLED


class MockRecognizer:
    """本地 mock：供 dev/E2E 联调；生产识别结果由端侧上报 trade-service。"""

    def __init__(self) -> None:
        self.load_error: str | None = None
        self.model_path = "n/a"
        self.model_version = "mock-dev"
        self.recognition_mode = "edge-deferred"

    @property
    def available(self) -> bool:
        return MOCK_ENABLED or not use_real_inference()

    def recognize(
        self,
        session_id: str,
        video_uri: str | None,
        device_id: str | None = None,
        recognition_mode: str | None = None,
    ) -> RecognitionOutput:
        if (recognition_mode or "").upper() == "INVENTORY_SNAPSHOT":
            return self._inventory_snapshot(session_id, device_id)
        if use_real_inference():
            log.warning(
                "real inference requested but no cloud model; session=%s need_review",
                session_id,
            )
            return self._empty_review("edge-provider-required")
        return self._mock(session_id, video_uri, device_id=device_id)

    def recognize_upload(
        self,
        session_id: str,
        data: bytes,
        filename: str,
        device_id: str | None = None,
    ) -> RecognitionOutput:
        upload_dir = Path(VIDEO_CACHE_DIR) / "uploads"
        upload_dir.mkdir(parents=True, exist_ok=True)
        ext = Path(filename).suffix.lower() if filename else ".jpg"
        if ext not in {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".mp4", ".avi", ".mov"}:
            ext = ".jpg"
        local = upload_dir / f"{session_id}{ext}"
        local.write_bytes(data)
        if use_real_inference():
            return self._empty_review("edge-provider-required")
        return self._mock(session_id, f"upload://{filename}", device_id=device_id)

    def _inventory_snapshot(self, session_id: str, device_id: str | None) -> RecognitionOutput:
        if use_real_inference():
            return self._empty_review("inventory-snapshot-unavailable")
        return self._mock_inventory_snapshot(device_id)

    def _mock_inventory_snapshot(self, device_id: str | None) -> RecognitionOutput:
        rows = fetch_inventory_snapshot(device_id)
        if not rows:
            sku_id = fetch_default_sku(device_id)
            rows = [(sku_id, 1)]
        items = [RecognizedItem(sku_id=sku, quantity=qty, confidence=0.95) for sku, qty in rows]
        return RecognitionOutput(
            items=items,
            overall_confidence=0.95,
            model_version="inventory-snapshot-mock",
            need_review=True,
        )

    def _empty_review(self, model_version: str) -> RecognitionOutput:
        return RecognitionOutput(
            items=[],
            overall_confidence=0.0,
            model_version=model_version,
            need_review=True,
            detected_classes=[],
        )

    def _mock(
        self,
        session_id: str,
        video_uri: str | None,
        need_review: bool | None = None,
        device_id: str | None = None,
    ) -> RecognitionOutput:
        if need_review is None:
            need_review = True
        conf = 0.75 if need_review else (0.92 if video_uri else 0.75)
        sku_id = fetch_default_sku(device_id) if MOCK_ENABLED else MOCK_SKU
        return RecognitionOutput(
            items=[RecognizedItem(sku_id=sku_id, quantity=1, confidence=conf)],
            overall_confidence=conf,
            model_version="mock-v1",
            need_review=need_review,
        )
