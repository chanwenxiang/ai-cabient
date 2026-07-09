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
_default_sku_cache: tuple[str, float] | None = None  # (sku_id, cached_at)
_cache_at = 0.0


def _trade_headers() -> dict[str, str]:
    return {"X-Internal-Api-Key": INTERNAL_API_KEY}


def _refresh_if_needed() -> None:
    global _yolo_cache, _aliyun_cache, _cache_at
    if _yolo_cache is not None and (time.time() - _cache_at) < CACHE_TTL_SECONDS:
        return
    try:
        with httpx.Client(timeout=5.0) as client:
            resp = client.get(
                f"{TRADE_SERVICE_URL}/internal/v1/vision/mappings",
                headers=_trade_headers(),
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
        _yolo_cache = yolo
        _aliyun_cache = aliyun
        _cache_at = time.time()
        log.info("vision mappings loaded yolo=%d aliyun=%d", len(_yolo_cache), len(_aliyun_cache))
    except Exception as exc:
        log.warning("load vision mappings failed: %s", exc)
        _yolo_cache = {}
        _aliyun_cache = {}
        _cache_at = time.time()


def fetch_default_sku(device_id: str | None = None) -> str:
    """从 trade 柜机库存解析可结算 SKU；失败时回退环境变量。"""
    global _default_sku_cache
    fallback = os.getenv("MOCK_SKU_ID", "SKU-DEMO-001")
    now = time.time()
    if _default_sku_cache and (now - _default_sku_cache[1]) < CACHE_TTL_SECONDS:
        return _default_sku_cache[0]
    try:
        params = {"deviceId": device_id} if device_id else {}
        with httpx.Client(timeout=5.0) as client:
            resp = client.get(
                f"{TRADE_SERVICE_URL}/internal/v1/vision/default-sku",
                headers=_trade_headers(),
                params=params,
            )
            resp.raise_for_status()
            sku_id = str(resp.json().get("data", {}).get("skuId") or fallback)
        _default_sku_cache = (sku_id, now)
        log.info("default mock sku from db device=%s sku=%s", device_id, sku_id)
        return sku_id
    except Exception as exc:
        log.warning("fetch default sku failed, use env fallback: %s", exc)
        return fallback


def fetch_inventory_snapshot(device_id: str | None) -> list[tuple[str, int]]:
    """从 trade 拉取柜内 SKU 汇总数量（补货库存快照 mock）。"""
    if not device_id:
        return []
    try:
        with httpx.Client(timeout=5.0) as client:
            resp = client.get(
                f"{TRADE_SERVICE_URL}/internal/v1/devices/{device_id}/inventory-snapshot",
                headers=_trade_headers(),
            )
            resp.raise_for_status()
            rows = resp.json().get("data") or []
        result: list[tuple[str, int]] = []
        for row in rows:
            sku = str(row.get("skuId") or "")
            qty = int(row.get("quantity") or 0)
            if sku and qty > 0:
                result.append((sku, qty))
        log.info("inventory snapshot loaded device=%s skus=%d", device_id, len(result))
        return result
    except Exception as exc:
        log.warning("fetch inventory snapshot failed device=%s: %s", device_id, exc)
        return []


def yolo_class_to_sku() -> dict[str, tuple[str, float]]:
    _refresh_if_needed()
    return dict(_yolo_cache or {})


def aliyun_category_to_sku() -> dict[str, tuple[str, float, str]]:
    _refresh_if_needed()
    return dict(_aliyun_cache or {})
