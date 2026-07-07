"""本地 YOLO 识别（开发/兜底）。"""

from __future__ import annotations

import logging
import os
from pathlib import Path

from app.recognition.mapping_client import yolo_class_to_sku
from app.recognition.types import RecognizedItem, RecognitionOutput
from app.storage import VIDEO_CACHE_DIR, resolve_video_path

log = logging.getLogger(__name__)

SERVICE_ROOT = Path(__file__).resolve().parent.parent.parent

MOCK_SKU = os.getenv("MOCK_SKU_ID", "SKU-DEMO-001")
CONF_THRESHOLD = float(os.getenv("YOLO_CONF", "0.5"))
REVIEW_CONF_THRESHOLD = float(os.getenv("YOLO_REVIEW_CONF", "0.7"))
MOCK_ENABLED = os.getenv("MOCK_ENABLED", "true").lower() == "true"
AUTO_DOWNLOAD = os.getenv("YOLO_AUTO_DOWNLOAD", "true").lower() == "true"


def resolve_model_path() -> str:
    raw = os.getenv("YOLO_MODEL_PATH", "models/yolov8n.pt")
    path = Path(raw)
    if not path.is_absolute():
        path = SERVICE_ROOT / path
    return str(path)


MODEL_PATH = resolve_model_path()


class YoloRecognizer:
    def __init__(self) -> None:
        self._model = None
        self.load_error: str | None = None
        self.model_path = MODEL_PATH
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
            log.info("YOLO model loaded: %s", path)
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

    def recognize(self, session_id: str, video_uri: str | None) -> RecognitionOutput:
        if self._model is None or not video_uri:
            return self._mock(session_id, video_uri)

        local_path = resolve_video_path(video_uri)
        if local_path is None or not os.path.exists(local_path):
            log.warning("video not available session=%s uri=%s", session_id, video_uri)
            return self._mock(session_id, video_uri, need_review=not MOCK_ENABLED)

        return self._infer(local_path)

    def recognize_upload(self, session_id: str, data: bytes, filename: str) -> RecognitionOutput:
        upload_dir = Path(VIDEO_CACHE_DIR) / "uploads"
        upload_dir.mkdir(parents=True, exist_ok=True)
        ext = Path(filename).suffix.lower() if filename else ".jpg"
        if ext not in {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".mp4", ".avi", ".mov"}:
            ext = ".jpg"
        local = upload_dir / f"{session_id}{ext}"
        local.write_bytes(data)

        if self._model is None:
            return self._mock(session_id, f"file:///{local.as_posix()}", need_review=not MOCK_ENABLED)

        return self._infer(str(local))

    def _prepare_inference_path(self, local_path: str) -> str:
        """短视频取中间帧做识别，避免整段视频逐帧推理过慢。"""
        ext = Path(local_path).suffix.lower()
        if ext not in {".mp4", ".avi", ".mov", ".mkv", ".webm", ".m4v"}:
            return local_path
        try:
            import cv2  # type: ignore
        except ImportError:
            return local_path

        cap = cv2.VideoCapture(local_path)
        if not cap.isOpened():
            log.warning("cannot open video for frame extract path=%s", local_path)
            return local_path

        frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
        target = max(frame_count // 2, 0)
        cap.set(cv2.CAP_PROP_POS_FRAMES, target)
        ok, frame = cap.read()
        cap.release()
        if not ok or frame is None:
            log.warning("no frame extracted path=%s", local_path)
            return local_path

        frame_dir = Path(VIDEO_CACHE_DIR) / "frames"
        frame_dir.mkdir(parents=True, exist_ok=True)
        stem = Path(local_path).stem
        out = frame_dir / f"{stem}_mid.jpg"
        cv2.imwrite(str(out), frame)
        log.info("extracted middle frame video=%s -> %s", local_path, out)
        return str(out)

    def _infer(self, local_path: str) -> RecognitionOutput:
        infer_path = self._prepare_inference_path(local_path)
        class_to_sku = {k: v[0] for k, v in yolo_class_to_sku().items()}
        results = self._model.predict(infer_path, conf=CONF_THRESHOLD, verbose=False)
        counts: dict[str, int] = {}
        conf_sum = 0.0
        conf_n = 0
        detected: list[str] = []

        for r in results:
            if r.boxes is None:
                continue
            for box in r.boxes:
                cls_id = int(box.cls[0])
                name = r.names.get(cls_id, str(cls_id))
                detected.append(name)
                sku = class_to_sku.get(name)
                if not sku:
                    continue
                counts[sku] = counts.get(sku, 0) + 1
                conf_sum += float(box.conf[0])
                conf_n += 1

        if not counts:
            return RecognitionOutput(
                items=[],
                overall_confidence=0.0,
                model_version="yolov8",
                need_review=True,
                detected_classes=sorted(set(detected)),
            )

        items = [
            RecognizedItem(sku_id=sku, quantity=qty, confidence=min(0.99, conf_sum / max(conf_n, 1)))
            for sku, qty in counts.items()
        ]
        overall = conf_sum / conf_n if conf_n else 0.0
        need_review = self._decide_need_review(counts, overall)
        return RecognitionOutput(
            items=items,
            overall_confidence=round(overall, 3),
            model_version="yolov8",
            need_review=need_review,
            detected_classes=sorted(set(detected)),
        )

    def _decide_need_review(self, counts: dict[str, int], overall: float) -> bool:
        if not counts:
            return True
        if MOCK_ENABLED:
            return False
        return overall < REVIEW_CONF_THRESHOLD

    def _mock(self, session_id: str, video_uri: str | None, need_review: bool | None = None) -> RecognitionOutput:
        if need_review is None:
            need_review = not MOCK_ENABLED or not video_uri
        conf = 0.92 if video_uri and not need_review else 0.75
        return RecognitionOutput(
            items=[RecognizedItem(sku_id=MOCK_SKU, quantity=1, confidence=conf)],
            overall_confidence=conf,
            model_version="mock-v1" if self._model is None else "yolov8-fallback",
            need_review=need_review,
        )
