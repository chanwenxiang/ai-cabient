"""对象存储：MinIO（本地）/ OSS（生产，S3 兼容 API）。"""

from __future__ import annotations

import logging
import os
from datetime import timedelta
from pathlib import Path
from urllib.parse import urlparse

log = logging.getLogger(__name__)

OBJECT_STORAGE_ENDPOINT = os.getenv("MINIO_ENDPOINT", "http://localhost:9000")
OBJECT_STORAGE_ACCESS_KEY = os.getenv("MINIO_ACCESS_KEY", "minioadmin")
OBJECT_STORAGE_SECRET_KEY = os.getenv("MINIO_SECRET_KEY", "minioadmin")
OBJECT_STORAGE_REGION = os.getenv("OSS_REGION", "")
VIDEO_CACHE_DIR = os.getenv("VIDEO_CACHE_DIR", "cache/videos")

# 兼容旧名
MINIO_ENDPOINT = OBJECT_STORAGE_ENDPOINT


def _client_host() -> tuple[str, bool]:
    parsed = urlparse(
        OBJECT_STORAGE_ENDPOINT if "://" in OBJECT_STORAGE_ENDPOINT else f"http://{OBJECT_STORAGE_ENDPOINT}"
    )
    host = parsed.netloc or parsed.path
    secure = parsed.scheme == "https"
    return host, secure


def parse_object_uri(uri: str) -> tuple[str, str] | None:
    """解析 minio:// 或 oss://bucket/key。"""
    for prefix in ("minio://", "oss://", "s3://"):
        if uri.startswith(prefix):
            rest = uri[len(prefix) :]
            if "/" not in rest:
                return None
            bucket, key = rest.split("/", 1)
            return bucket, key
    return None


def resolve_video_path(video_uri: str | None) -> str | None:
    """将 file:// 或 minio:// / oss:// URI 解析为本地可读路径。"""
    if not video_uri:
        return None
    if video_uri.startswith("file://"):
        path = video_uri[7:]
        return path if os.path.exists(path) else None
    parsed = parse_object_uri(video_uri)
    if parsed is None:
        return None
    return _download_object(*parsed)


def presign_object_url(video_uri: str, expires_seconds: int = 3600) -> str | None:
    """生成对象可读 URL，供阿里云商品理解等云端 API 使用。"""
    parsed = parse_object_uri(video_uri)
    if parsed is None:
        return None
    bucket, key = parsed
    try:
        from minio import Minio  # type: ignore

        host, secure = _client_host()
        client = Minio(
            host,
            access_key=OBJECT_STORAGE_ACCESS_KEY,
            secret_key=OBJECT_STORAGE_SECRET_KEY,
            secure=secure,
            region=OBJECT_STORAGE_REGION or None,
        )
        return client.presigned_get_object(
            bucket, key, expires=timedelta(seconds=expires_seconds)
        )
    except Exception as exc:
        log.warning("presign failed uri=%s err=%s", video_uri, exc)
        return None


def _download_object(bucket: str, key: str) -> str | None:
    cache_dir = Path(VIDEO_CACHE_DIR)
    cache_dir.mkdir(parents=True, exist_ok=True)
    safe_name = key.replace("/", "_").replace("\\", "_")
    local = cache_dir / f"{bucket}_{safe_name}"

    if local.exists() and local.stat().st_size > 0:
        return str(local)

    try:
        from minio import Minio  # type: ignore

        host, secure = _client_host()
        client = Minio(
            host,
            access_key=OBJECT_STORAGE_ACCESS_KEY,
            secret_key=OBJECT_STORAGE_SECRET_KEY,
            secure=secure,
            region=OBJECT_STORAGE_REGION or None,
        )
        client.fget_object(bucket, key, str(local))
        log.info("downloaded %s/%s -> %s", bucket, key, local)
        return str(local)
    except Exception as exc:
        log.warning("object download failed bucket=%s key=%s err=%s", bucket, key, exc)
        return None
