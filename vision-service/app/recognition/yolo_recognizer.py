"""本地 YOLO 识别（开发/兜底）。"""

from __future__ import annotations

import logging
import os
from pathlib import Path

from app.recognition.delta_recognizer import compute_delta
from app.recognition.frame_extract import extract_key_frames, is_video_path
from app.recognition.mapping_client import fetch_default_sku, fetch_inventory_snapshot, yolo_class_to_sku
from app.recognition.model_meta import resolve_model_path, resolve_model_version
from app.recognition.types import RecognizedItem, RecognitionOutput
from app.storage import VIDEO_CACHE_DIR, resolve_video_path

log = logging.getLogger(__name__)

SERVICE_ROOT = Path(__file__).resolve().parent.parent.parent

MOCK_SKU = os.getenv("MOCK_SKU_ID", "SKU-DEMO-001")
CONF_THRESHOLD = float(os.getenv("YOLO_CONF", "0.5"))
REVIEW_CONF_THRESHOLD = float(os.getenv("YOLO_REVIEW_CONF", "0.7"))
MOCK_ENABLED = os.getenv("MOCK_ENABLED", "true").lower() == "true"
VISION_FORCE_REAL = os.getenv("VISION_FORCE_REAL", "false").lower() == "true"
# Runtime-togglable for dispute E2E without recreating the container.
_force_need_review = os.getenv("MOCK_FORCE_NEED_REVIEW", "false").lower() == "true"
AUTO_DOWNLOAD = os.getenv("YOLO_AUTO_DOWNLOAD", "true").lower() == "true"
RECOGNITION_MODE = os.getenv("YOLO_RECOGNITION_MODE", "delta").lower()


def get_force_need_review() -> bool:
    return _force_need_review


def set_force_need_review(enabled: bool) -> bool:
    global _force_need_review
    _force_need_review = bool(enabled)
    log.info("MOCK_FORCE_NEED_REVIEW set to %s", _force_need_review)
    return _force_need_review

MODEL_PATH = resolve_model_path()
MODEL_VERSION = resolve_model_version(MODEL_PATH)


def use_real_inference() -> bool:
    """真实推理路径：失败时不静默 mock。"""
    return VISION_FORCE_REAL or not MOCK_ENABLED


class YoloRecognizer:
    def __init__(self) -> None:
        self._model = None
        self.load_error: str | None = None
        self.model_path = MODEL_PATH
        self.model_version = MODEL_VERSION
        self.recognition_mode = RECOGNITION_MODE
        self._load_model()

    def _load_model(self) -> None:
        try:
            from ultralytics import YOLO  # type: ignore
        except ImportError as exc:
            self.load_error = f"ultralytics not installed: {exc}"
            log.warning("ultralytics not installed")
            return

        path = self.model_path
        if not os.path.exists(path):
            if not AUTO_DOWNLOAD:
                self.load_error = f"model not found: {path}"
                return
            try:
                self._ensure_model_file(path, YOLO)
            except Exception as exc:
                self.load_error = str(exc)
                return

        try:
            self._model = YOLO(path)
            self.load_error = None
            log.info("YOLO model loaded: %s mode=%s", path, self.recognition_mode)
        except Exception as exc:
            self.load_error = str(exc)
            self._model = None

    @staticmethod
    def _ensure_model_file(path: str, yolo_cls) -> None:
        parent = os.path.dirname(path)
        if parent:
            os.makedirs(parent, exist_ok=True)
        model = yolo_cls("yolov8n.pt")
        if hasattr(model, "ckpt_path") and model.ckpt_path and os.path.exists(str(model.ckpt_path)):
            src = str(model.ckpt_path)
            if os.path.abspath(src) != os.path.abspath(path):
                import shutil
                shutil.copy2(src, path)

    @property
    def available(self) -> bool:
        return self._model is not None

    def recognize(self, session_id: str, video_uri: str | None, device_id: str | None = None,
                  recognition_mode: str | None = None) -> RecognitionOutput:
        if (recognition_mode or "").upper() == "INVENTORY_SNAPSHOT":
            return self._inventory_snapshot(session_id, video_uri, device_id)
        if self._model is None:
            return self._on_failure(session_id, video_uri, "model-unavailable", device_id=device_id)
        if not video_uri:
            return self._on_failure(session_id, video_uri, "missing-video-uri", device_id=device_id)

        local_path = resolve_video_path(video_uri)
        if local_path is None or not os.path.exists(local_path):
            log.warning("video not available session=%s uri=%s", session_id, video_uri)
            return self._on_failure(session_id, video_uri, "video-download-failed", device_id=device_id)

        return self._infer_media(local_path)

    def recognize_upload(self, session_id: str, data: bytes, filename: str) -> RecognitionOutput:
        upload_dir = Path(VIDEO_CACHE_DIR) / "uploads"
        upload_dir.mkdir(parents=True, exist_ok=True)
        ext = Path(filename).suffix.lower() if filename else ".jpg"
        if ext not in {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".mp4", ".avi", ".mov"}:
            ext = ".jpg"
        local = upload_dir / f"{session_id}{ext}"
        local.write_bytes(data)

        if self._model is None:
            return self._empty_review(self.model_version, [])

        return self._infer_media(str(local))

    def _infer_media(self, local_path: str) -> RecognitionOutput:
        mode = self.recognition_mode
        if mode == "delta" and is_video_path(local_path):
            return self._infer_delta(local_path)
        return self._infer_single(local_path)

    def _infer_delta(self, local_path: str) -> RecognitionOutput:
        frames = extract_key_frames(local_path)
        open_path = frames.get("open")
        close_path = frames.get("close")
        if not open_path or not close_path:
            log.warning("delta frame extract incomplete path=%s frames=%s", local_path, frames)
            return self._infer_single(local_path)

        open_counts, open_detected = self._count_skus(open_path)
        close_counts, close_detected = self._count_skus(close_path)
        detected = sorted(set(open_detected + close_detected))
        log.info(
            "delta infer path=%s open_skus=%s close_skus=%s detected=%s",
            local_path,
            list(open_counts.keys()),
            list(close_counts.keys()),
            detected,
        )
        out = compute_delta(
            open_counts,
            close_counts,
            model_version=self.model_version,
            detected_classes=detected,
        )
        if not out.need_review:
            out.need_review = self._decide_need_review(
                {i.sku_id: i.quantity for i in out.items},
                out.overall_confidence,
            )
        return out

    def _infer_single(self, local_path: str) -> RecognitionOutput:
        infer_path = self._prepare_inference_path(local_path)
        counts, detected = self._count_skus(infer_path)
        log.info(
            "single infer path=%s frame=%s mapped_skus=%s detected=%s",
            local_path,
            infer_path,
            list(counts.keys()),
            detected,
        )
        if not counts:
            return RecognitionOutput(
                items=[],
                overall_confidence=0.0,
                model_version=self.model_version,
                need_review=True,
                detected_classes=sorted(set(detected)),
            )

        items = [
            RecognizedItem(sku_id=sku, quantity=qty, confidence=min(0.99, conf))
            for sku, (qty, conf) in counts.items()
        ]
        conf_values = [conf for _, (_, conf) in counts.items()]
        overall = sum(conf_values) / len(conf_values) if conf_values else 0.0
        need_review = self._decide_need_review({i.sku_id: i.quantity for i in items}, overall)
        return RecognitionOutput(
            items=items,
            overall_confidence=round(overall, 3),
            model_version=self.model_version,
            need_review=need_review,
            detected_classes=sorted(set(detected)),
        )

    def _count_skus(self, infer_path: str) -> tuple[dict[str, tuple[int, float]], list[str]]:
        class_to_sku = yolo_class_to_sku()
        if self._model is None:
            return {}, []
        results = self._model.predict(infer_path, conf=CONF_THRESHOLD, verbose=False)
        counts: dict[str, tuple[int, float]] = {}
        conf_sum: dict[str, float] = {}
        conf_n: dict[str, int] = {}
        detected: list[str] = []

        for r in results:
            if r.boxes is None:
                continue
            for box in r.boxes:
                cls_id = int(box.cls[0])
                name = r.names.get(cls_id, str(cls_id))
                detected.append(name)
                mapping = class_to_sku.get(name)
                if not mapping:
                    continue
                sku, min_conf = mapping
                box_conf = float(box.conf[0])
                if box_conf < min_conf:
                    continue
                conf_sum[sku] = conf_sum.get(sku, 0.0) + box_conf
                conf_n[sku] = conf_n.get(sku, 0) + 1
                prev_qty, _ = counts.get(sku, (0, 0.0))
                counts[sku] = (prev_qty + 1, 0.0)

        for sku, qty_tuple in list(counts.items()):
            avg_conf = conf_sum[sku] / max(conf_n[sku], 1)
            counts[sku] = (qty_tuple[0], avg_conf)

        return counts, detected

    def _prepare_inference_path(self, local_path: str) -> str:
        """短视频取中间帧；图片原样返回。"""
        if not is_video_path(local_path):
            return local_path
        frames = extract_key_frames(local_path)
        mid = frames.get("mid")
        if mid:
            return mid
        return local_path

    def _decide_need_review(self, counts: dict[str, int], overall: float) -> bool:
        if not counts:
            return True
        if MOCK_ENABLED and not VISION_FORCE_REAL:
            return False
        return overall < REVIEW_CONF_THRESHOLD

    def _inventory_snapshot(self, session_id: str, video_uri: str | None,
                            device_id: str | None) -> RecognitionOutput:
        if self._model is not None and video_uri:
            local_path = resolve_video_path(video_uri)
            if local_path is not None and os.path.exists(local_path):
                out = self._infer_media(local_path)
                if out.items:
                    out.model_version = f"{self.model_version}-inventory-snapshot"
                    return out
                log.info("inventory snapshot yolo empty session=%s fallback book", session_id)
            else:
                log.warning("inventory snapshot video missing session=%s uri=%s", session_id, video_uri)
        if use_real_inference():
            return self._empty_review(f"{self.model_version}-inventory-snapshot", [])
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
            need_review=False,
        )

    def _on_failure(
        self,
        session_id: str,
        video_uri: str | None,
        reason: str,
        device_id: str | None = None,
    ) -> RecognitionOutput:
        log.warning("recognition failure session=%s reason=%s uri=%s", session_id, reason, video_uri)
        if use_real_inference():
            return self._empty_review(
                f"{self.model_version}-failed" if self._model else "model-unavailable",
                [],
            )
        return self._mock(session_id, video_uri, device_id=device_id)

    def _empty_review(self, model_version: str, detected: list[str]) -> RecognitionOutput:
        return RecognitionOutput(
            items=[],
            overall_confidence=0.0,
            model_version=model_version,
            need_review=True,
            detected_classes=detected,
        )

    def _mock(self, session_id: str, video_uri: str | None, need_review: bool | None = None,
              device_id: str | None = None) -> RecognitionOutput:
        if need_review is None:
            if get_force_need_review():
                need_review = True
            else:
                need_review = False if MOCK_ENABLED else not video_uri
        conf = 0.92 if video_uri and not need_review else 0.75
        sku_id = fetch_default_sku(device_id) if MOCK_ENABLED else MOCK_SKU
        return RecognitionOutput(
            items=[RecognizedItem(sku_id=sku_id, quantity=1, confidence=conf)],
            overall_confidence=conf,
            model_version="mock-v1" if self._model is None else f"{self.model_version}-fallback",
            need_review=need_review,
        )
