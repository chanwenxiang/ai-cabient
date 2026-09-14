"""对象存储：MinIO（本地）/ OSS（生产，S3 兼容 API）。"""

from __future__ import annotations

import logging
import os
import threading
import time
from datetime import timedelta
from pathlib import Path
from urllib.parse import urlparse

log = logging.getLogger(__name__)

OBJECT_STORAGE_ENDPOINT = os.getenv("MINIO_ENDPOINT", "http://localhost:9000")
OBJECT_STORAGE_ACCESS_KEY = os.getenv("MINIO_ACCESS_KEY", "minioadmin")
OBJECT_STORAGE_SECRET_KEY = os.getenv("MINIO_SECRET_KEY", "minioadmin")
OBJECT_STORAGE_REGION = os.getenv("OSS_REGION", "")
VIDEO_CACHE_DIR = os.getenv("VIDEO_CACHE_DIR", "cache/videos")

# V-P2-2：本地缓存与远端对象过期策略（可环境变量覆盖）
VIDEO_CACHE_TTL_HOURS = max(1, int(os.getenv("VIDEO_CACHE_TTL_HOURS", "72")))
VIDEO_CACHE_PURGE_INTERVAL_SEC = max(60, int(os.getenv("VIDEO_CACHE_PURGE_INTERVAL_SEC", "3600")))
OBJECT_STORAGE_LIFECYCLE_DAYS = max(1, int(os.getenv("OBJECT_STORAGE_LIFECYCLE_DAYS", "14")))
# 逗号分隔桶名；空则跳过远端 lifecycle（由运维在 OSS 控制台配置）
OBJECT_STORAGE_LIFECYCLE_BUCKETS = [
    b.strip()
    for b in os.getenv("OBJECT_STORAGE_LIFECYCLE_BUCKETS", os.getenv("MINIO_BUCKET", "aicabinet")).split(",")
    if b.strip()
]

# 兼容旧名
MINIO_ENDPOINT = OBJECT_STORAGE_ENDPOINT

_purge_lock = threading.Lock()
_last_purge_at = 0.0


def _client_host() -> tuple[str, bool]:
    parsed = urlparse(
        OBJECT_STORAGE_ENDPOINT if "://" in OBJECT_STORAGE_ENDPOINT else f"http://{OBJECT_STORAGE_ENDPOINT}"
    )
    host = parsed.netloc or parsed.path
    secure = parsed.scheme == "https"
    return host, secure


def _minio_client():
    from minio import Minio  # type: ignore

    host, secure = _client_host()
    return Minio(
        host,
        access_key=OBJECT_STORAGE_ACCESS_KEY,
        secret_key=OBJECT_STORAGE_SECRET_KEY,
        secure=secure,
        region=OBJECT_STORAGE_REGION or None,
    )


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


def _is_under_cache_root(path: Path) -> bool:
    """仅允许 VIDEO_CACHE_DIR 内路径，防 file:// 任意读。"""
    try:
        root = Path(VIDEO_CACHE_DIR).resolve()
        path.resolve().relative_to(root)
        return True
    except (ValueError, OSError):
        return False


def resolve_video_path(video_uri: str | None) -> str | None:
    """将 file:// 或 minio:// / oss:// URI 解析为本地可读路径。"""
    if not video_uri:
        return None
    if video_uri.startswith("file://"):
        raw = video_uri[7:]
        # file:///C:/... → /C:/... ；去掉盘符前多余斜杠
        if len(raw) >= 3 and raw[0] == "/" and raw[2] == ":":
            raw = raw[1:]
        path = Path(raw)
        if not _is_under_cache_root(path):
            log.warning("rejected file:// outside VIDEO_CACHE_DIR uri=%s", video_uri)
            return None
        resolved = path.resolve()
        return str(resolved) if resolved.is_file() else None
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
        client = _minio_client()
        return client.presigned_get_object(
            bucket, key, expires=timedelta(seconds=expires_seconds)
        )
    except Exception as exc:
        log.warning("presign failed uri=%s err=%s", video_uri, exc)
        return None


def purge_expired_local_cache(ttl_hours: int | None = None, now: float | None = None) -> int:
    """
    V-P2-2：清理 VIDEO_CACHE_DIR 内超过 TTL 的文件（含 uploads/frames）。
    返回删除文件数；目录本身保留。
    """
    hours = VIDEO_CACHE_TTL_HOURS if ttl_hours is None else max(1, int(ttl_hours))
    cutoff = (now if now is not None else time.time()) - hours * 3600
    root = Path(VIDEO_CACHE_DIR)
    if not root.exists():
        return 0
    deleted = 0
    try:
        for path in root.rglob("*"):
            if not path.is_file():
                continue
            try:
                if path.stat().st_mtime < cutoff:
                    path.unlink(missing_ok=True)
                    deleted += 1
            except OSError as exc:
                log.debug("purge skip %s: %s", path, exc)
    except OSError as exc:
        log.warning("purge_expired_local_cache failed: %s", exc)
        return deleted
    if deleted:
        log.info("purged %s expired cache files under %s (ttl=%sh)", deleted, root, hours)
    return deleted


def maybe_purge_local_cache(force: bool = False) -> int:
    """节流后的本地缓存清理（下载路径可调用）。"""
    global _last_purge_at
    now = time.time()
    with _purge_lock:
        if not force and (now - _last_purge_at) < VIDEO_CACHE_PURGE_INTERVAL_SEC:
            return 0
        _last_purge_at = now
    return purge_expired_local_cache(now=now)


def ensure_object_lifecycle(
    buckets: list[str] | None = None,
    days: int | None = None,
) -> dict[str, str]:
    """
    V-P2-2：为 MinIO/S3 兼容桶设置「到期删除」lifecycle。
    OSS 正式环境若无权限或非 MinIO API，失败仅记日志，需控制台补配。
    """
    targets = buckets if buckets is not None else OBJECT_STORAGE_LIFECYCLE_BUCKETS
    expire_days = OBJECT_STORAGE_LIFECYCLE_DAYS if days is None else max(1, int(days))
    results: dict[str, str] = {}
    if not targets:
        return results
    try:
        from minio.commonconfig import ENABLED, Filter  # type: ignore
        from minio.lifecycleconfig import Expiration, LifecycleConfig, Rule  # type: ignore

        client = _minio_client()
        config = LifecycleConfig(
            [
                Rule(
                    ENABLED,
                    rule_filter=Filter(prefix=""),
                    rule_id="aicabinet-expire-objects",
                    expiration=Expiration(days=expire_days),
                )
            ]
        )
        for bucket in targets:
            try:
                if not client.bucket_exists(bucket):
                    results[bucket] = "missing"
                    log.warning("lifecycle skip missing bucket=%s", bucket)
                    continue
                client.set_bucket_lifecycle(bucket, config)
                results[bucket] = f"expire-{expire_days}d"
                log.info("set bucket lifecycle bucket=%s days=%s", bucket, expire_days)
            except Exception as exc:
                results[bucket] = f"error:{exc}"
                log.warning("set lifecycle failed bucket=%s err=%s", bucket, exc)
    except Exception as exc:
        log.warning("ensure_object_lifecycle unavailable: %s", exc)
    return results


def start_cache_maintenance() -> threading.Thread | None:
    """启动时清一次缓存，并按间隔后台 purge；同时尝试配置远端 lifecycle。"""
    maybe_purge_local_cache(force=True)
    ensure_object_lifecycle()

    def run() -> None:
        while True:
            time.sleep(VIDEO_CACHE_PURGE_INTERVAL_SEC)
            try:
                maybe_purge_local_cache(force=True)
            except Exception as exc:
                log.warning("cache maintenance loop error: %s", exc)

    thread = threading.Thread(target=run, name="vision-cache-maintenance", daemon=True)
    thread.start()
    return thread


def _download_object(bucket: str, key: str) -> str | None:
    maybe_purge_local_cache()
    cache_dir = Path(VIDEO_CACHE_DIR)
    cache_dir.mkdir(parents=True, exist_ok=True)
    safe_name = key.replace("/", "_").replace("\\", "_")
    local = cache_dir / f"{bucket}_{safe_name}"

    if local.exists() and local.stat().st_size > 0:
        try:
            local.touch()
        except OSError:
            pass
        return str(local)

    try:
        client = _minio_client()
        client.fget_object(bucket, key, str(local))
        log.info("downloaded %s/%s -> %s", bucket, key, local)
        return str(local)
    except Exception as exc:
        log.warning("object download failed bucket=%s key=%s err=%s", bucket, key, exc)
        return None
