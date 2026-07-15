"""阿里云视觉智能开放平台 — 商品理解 ClassifyCommodity（非自研 CV）。"""

from __future__ import annotations

import logging
import os

from app.recognition.mapping_client import aliyun_category_to_sku
from app.recognition.types import RecognizedItem, RecognitionOutput
from app.storage import presign_object_url, resolve_video_path

log = logging.getLogger(__name__)

MOCK_ENABLED = os.getenv("MOCK_ENABLED", "true").lower() == "true"
REVIEW_CONF_THRESHOLD = float(os.getenv("ALIYUN_REVIEW_CONF", "0.7"))
ACCESS_KEY_ID = os.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID", "")
ACCESS_KEY_SECRET = os.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET", "")
GOODSTECH_ENDPOINT = os.getenv(
    "ALIYUN_GOODSTECH_ENDPOINT", "goodstech.cn-shanghai.aliyuncs.com"
)


class AliyunGoodsRecognizer:
    """生产推荐：关门图 OSS URL → ClassifyCommodity → 类目映射 SKU。"""

    def __init__(self) -> None:
        self.load_error: str | None = None
        self._client = None
        self._init_client()

    def _init_client(self) -> None:
        if not ACCESS_KEY_ID or not ACCESS_KEY_SECRET:
            self.load_error = "ALIBABA_CLOUD_ACCESS_KEY_ID/SECRET not set"
            return
        try:
            from alibabacloud_goodstech20191230.client import Client  # type: ignore
            from alibabacloud_tea_openapi import models as open_api_models  # type: ignore

            config = open_api_models.Config(
                access_key_id=ACCESS_KEY_ID,
                access_key_secret=ACCESS_KEY_SECRET,
                endpoint=GOODSTECH_ENDPOINT,
            )
            self._client = Client(config)
            self.load_error = None
            log.info("aliyun goodstech client ready endpoint=%s", GOODSTECH_ENDPOINT)
        except ImportError:
            self.load_error = "pip install alibabacloud_goodstech20191230"
            log.warning(self.load_error)
        except Exception as exc:
            self.load_error = str(exc)
            log.error("aliyun client init failed: %s", exc)

    @property
    def available(self) -> bool:
        return self._client is not None

    @property
    def model_path(self) -> str:
        return f"aliyun://{GOODSTECH_ENDPOINT}"

    def recognize(self, session_id: str, video_uri: str | None) -> RecognitionOutput:
        if not video_uri:
            return self._empty("no video uri")
        if not self.available:
            return RecognitionOutput(
                items=[],
                overall_confidence=0.0,
                model_version="aliyun-unavailable",
                need_review=True,
                detected_classes=[self.load_error or "aliyun not configured"],
            )

        image_url = presign_object_url(video_uri)
        if not image_url:
            local = resolve_video_path(video_uri)
            if local and os.path.exists(local):
                return self._classify_local(local)
            return self._empty(f"cannot resolve image url for {video_uri}")

        return self._classify_url(image_url)

    def recognize_upload(self, session_id: str, data: bytes, filename: str) -> RecognitionOutput:
        """上传测试：写入临时文件后走本地文件识别（需 SDK 支持或先传 OSS）。"""
        from pathlib import Path
        from app.storage import VIDEO_CACHE_DIR

        upload_dir = Path(VIDEO_CACHE_DIR) / "uploads"
        upload_dir.mkdir(parents=True, exist_ok=True)
        ext = Path(filename).suffix.lower() if filename else ".jpg"
        local = upload_dir / f"{session_id}{ext}"
        local.write_bytes(data)
        if not self.available:
            return RecognitionOutput(
                items=[],
                overall_confidence=0.0,
                model_version="aliyun-unavailable",
                need_review=not MOCK_ENABLED,
            )
        return self._classify_local(str(local))

    def _classify_url(self, image_url: str) -> RecognitionOutput:
        try:
            from alibabacloud_goodstech20191230 import models as goodstech_models  # type: ignore

            req = goodstech_models.ClassifyCommodityRequest(image_url=image_url)
            resp = self._client.classify_commodity(req)
            return self._parse_response(resp)
        except Exception as exc:
            log.exception("aliyun ClassifyCommodity failed url=%s", image_url)
            return RecognitionOutput(
                items=[],
                overall_confidence=0.0,
                model_version="aliyun-error",
                need_review=True,
                detected_classes=[str(exc)],
            )

    def _classify_local(self, local_path: str) -> RecognitionOutput:
        """SDK 支持本地文件路径（上海 OSS 场景可自动上传）。"""
        try:
            from alibabacloud_goodstech20191230 import models as goodstech_models  # type: ignore

            req = goodstech_models.ClassifyCommodityAdvanceRequest()
            with open(local_path, "rb") as f:
                req.image_url_object = f
                resp = self._client.classify_commodity_advance(req, {}, {})
            return self._parse_response(resp)
        except Exception as exc:
            log.warning("aliyun advance classify failed, try presign: %s", exc)
            return self._empty(str(exc))

    def _parse_response(self, resp) -> RecognitionOutput:
        mapping = aliyun_category_to_sku()
        categories = []
        body = getattr(resp, "body", None)
        data = getattr(body, "data", None) if body else None
        if data and getattr(data, "categories", None):
            categories = data.categories

        detected: list[str] = []
        counts: dict[str, int] = {}
        conf_sum = 0.0
        conf_n = 0

        for cat in categories:
            cat_id = str(getattr(cat, "category_id", "") or "")
            cat_name = str(getattr(cat, "category_name", "") or cat_id)
            score = float(getattr(cat, "score", 0.0) or 0.0)
            detected.append(f"{cat_name}({cat_id})")

            mapped = mapping.get(cat_id)
            if not mapped:
                continue
            sku_id, min_conf, _ = mapped
            if score < min_conf:
                continue
            counts[sku_id] = counts.get(sku_id, 0) + 1
            conf_sum += score
            conf_n += 1

        if not counts:
            return RecognitionOutput(
                items=[],
                overall_confidence=0.0,
                model_version="aliyun-goodstech",
                need_review=True,
                detected_classes=detected,
            )

        overall = conf_sum / conf_n
        need_review = False if MOCK_ENABLED else overall < REVIEW_CONF_THRESHOLD
        items = [
            RecognizedItem(sku_id=sku, quantity=qty, confidence=min(0.99, overall))
            for sku, qty in counts.items()
        ]
        return RecognitionOutput(
            items=items,
            overall_confidence=round(overall, 3),
            model_version="aliyun-goodstech",
            need_review=need_review,
            detected_classes=detected,
        )

    def _empty(self, reason: str) -> RecognitionOutput:
        return RecognitionOutput(
            items=[],
            overall_confidence=0.0,
            model_version="aliyun-goodstech",
            need_review=True,
            detected_classes=[reason],
        )
