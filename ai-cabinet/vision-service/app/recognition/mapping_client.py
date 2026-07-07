"""从 trade-service 拉取 YOLO / 阿里云类目 → SKU 映射（带内存缓存）。"""

from __future__ import annotations

import logging
import os
import time
from typing import Any

import httpx

log = logging.getLogger(__name__)

TRADE_SERVICE_URL = os.getenv("TRADE_SERVICE_URL", "http://localhost:8080").rstrip("/")
INTERNAL_API_KEY = os.getenv("INTERNAL_API_KEY", "dev-internal-key-change-me")
CACHE_TTL_SECONDS = int(os.getenv("VISION_MAPPING_CACHE_TTL", "300"))

_yolo_cache: dict[str, tuple[str, float]] | None = None
_aliyun_cache: dict[str, tuple[str, float, str]] | None = None  # id -> (sku, min_conf, name)
_cache_at = 0.0

# 本地兜底（trade 不可达时）
DEFAULT_YOLO_MAP: dict[str, tuple[str, float]] = {
    "bottle": ("SKU-SODA-001", 0.5),
    "cup": ("SKU-SODA-001", 0.5),
    "apple": ("SKU-APPLE-001", 0.5),
}


def _refresh_if_needed() -> None:
    global _yolo_cache, _aliyun_cache, _cache_at
    if _yolo_cache is not None and (time.time() - _cache_at) < CACHE_TTL_SECONDS:
        return
    try:
        with httpx.Client(timeout=5.0) as client:
            resp = client.get(
                f"{TRADE_SERVICE_URL}/internal/v1/vision/mappings",
                headers={"X-Internal-Api-Key": INTERNAL_API_KEY},
            )
            resp.raise_for_status()
            body: dict[str, Any] = resp.json().get("data") or {}
        yolo: dict[str, tuple[str, float]] = {}
        for row in body.get("yolo") or []:
            yolo[str(row["className"])] = (str(row["skuId"]), float(row.get("minConfidence") or 0.5))
        aliyun: dict[str, tuple[str, float, str]] = {}
        for row in body.get("aliyun") or []:
            aliyun[str(row["categoryId"])] = (
                str(row["skuId"]),
                float(row.get("minConfidence") or 0.5),
                str(row.get("categoryName") or ""),
            )
        _yolo_cache = yolo or DEFAULT_YOLO_MAP.copy()
        _aliyun_cache = aliyun
        _cache_at = time.time()
        log.info("vision mappings loaded yolo=%d aliyun=%d", len(_yolo_cache), len(_aliyun_cache))
    except Exception as exc:
        log.warning("load vision mappings failed, use defaults: %s", exc)
        _yolo_cache = DEFAULT_YOLO_MAP.copy()
        _aliyun_cache = {}
        _cache_at = time.time()


def yolo_class_to_sku() -> dict[str, tuple[str, float]]:
    _refresh_if_needed()
    return dict(_yolo_cache or DEFAULT_YOLO_MAP)


def aliyun_category_to_sku() -> dict[str, tuple[str, float, str]]:
    _refresh_if_needed()
    return dict(_aliyun_cache or {})
