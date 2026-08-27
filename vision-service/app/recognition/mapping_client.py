"""从 trade-service 拉取端侧识别类名 → SKU 映射（带内存缓存）。"""

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

_class_cache: dict[str, tuple[str, float, str]] | None = None

_default_sku_cache: tuple[str, float] | None = None  # (sku_id, cached_at)
_cache_at = 0.0


def _trade_headers() -> dict[str, str]:
    return {"X-Internal-Api-Key": INTERNAL_API_KEY}


def _refresh_if_needed() -> None:
    global _class_cache, _cache_at
    if _class_cache is not None and (time.time() - _cache_at) < CACHE_TTL_SECONDS:
        return
    try:
        with httpx.Client(timeout=5.0) as client:
            resp = client.get(
                f"{TRADE_SERVICE_URL}/internal/v1/vision/mappings",
                headers=_trade_headers(),
            )
            resp.raise_for_status()
            body: dict[str, Any] = resp.json().get("data") or {}
        classes: dict[str, tuple[str, float, str]] = {}
        rows = body.get("yolo") or body.get("classes") or []
        for row in rows:
            classes[str(row["className"])] = (
                str(row["skuId"]),
                float(row.get("minConfidence") or 0.5),
                str(row.get("mappingSource") or "EDGE_CLASS"),
            )
        _class_cache = classes
        _cache_at = time.time()
        log.info("vision mappings loaded classes=%d items", len(_class_cache))
    except Exception as exc:
        log.warning("load vision mappings failed: %s", exc)
        _class_cache = {}
        _cache_at = time.time()


def fetch_device_vision_context(device_id: str | None) -> list[dict[str, Any]]:
    """柜机在售 SKU 白名单，供 DeepSeek constrained prompt。"""
    if not device_id:
        return []
    try:
        with httpx.Client(timeout=5.0) as client:
            resp = client.get(
                f"{TRADE_SERVICE_URL}/internal/v1/vision/device/{device_id}/context",
                headers=_trade_headers(),
            )
            resp.raise_for_status()
            body = resp.json().get("data") or {}
        rows = body.get("skus") or []
        return [
            {
                "skuId": str(row.get("skuId") or ""),
                "skuName": str(row.get("skuName") or ""),
                "yoloClassName": str(row.get("yoloClassName") or row.get("className") or ""),
                "priceCents": int(row.get("priceCents") or 0),
                "imageUrl": row.get("imageUrl"),
            }
            for row in rows
            if row.get("skuId")
        ]
    except Exception as exc:
        log.warning("fetch device vision context failed device=%s: %s", device_id, exc)
        return []


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


def class_to_sku() -> dict[str, tuple[str, float]]:
    _refresh_if_needed()
    return {k: (v[0], v[1]) for k, v in (_class_cache or {}).items()}


def yolo_class_to_sku() -> dict[str, tuple[str, float]]:
    """兼容旧调用方。"""
    return class_to_sku()
