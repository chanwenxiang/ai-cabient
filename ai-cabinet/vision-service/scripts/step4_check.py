"""Step 4 vision pipeline checks: upload, minio://, multi-camera fusion."""

from __future__ import annotations

import argparse
import json
import os
import sys
import uuid
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parent.parent
PROJECT_ROOT = ROOT.parent


def upload_minio(local: Path, object_key: str, endpoint: str, access: str, secret: str, bucket: str) -> str:
    from minio import Minio  # type: ignore

    host = endpoint.replace("http://", "").replace("https://", "")
    secure = endpoint.startswith("https://")
    client = Minio(host, access_key=access, secret_key=secret, secure=secure)
    if not client.bucket_exists(bucket):
        client.make_bucket(bucket)
    ext = local.suffix.lower()
    ctype = {
        ".jpg": "image/jpeg",
        ".jpeg": "image/jpeg",
        ".png": "image/png",
        ".mp4": "video/mp4",
    }.get(ext, "application/octet-stream")
    client.fput_object(bucket, object_key, str(local), content_type=ctype)
    return f"minio://{bucket}/{object_key}"


def post_recognize(base: str, api_key: str, payload: dict) -> dict:
    r = httpx.post(
        f"{base.rstrip('/')}/api/v2/vision/recognize",
        headers={"X-Internal-Api-Key": api_key},
        json=payload,
        timeout=120.0,
    )
    r.raise_for_status()
    return r.json()


def post_upload(base: str, api_key: str, session_id: str, image: Path) -> dict:
    with image.open("rb") as f:
        r = httpx.post(
            f"{base.rstrip('/')}/api/v2/vision/recognize/upload",
            headers={"X-Internal-Api-Key": api_key},
            data={"session_id": session_id},
            files={"file": (image.name, f, "image/jpeg")},
            timeout=120.0,
        )
    r.raise_for_status()
    return r.json()


def resolve_sample_image(explicit: str | None) -> Path:
    if explicit:
        p = Path(explicit)
        if not p.is_file():
            raise FileNotFoundError(f"sample image not found: {p}")
        return p
    for candidate in (
        PROJECT_ROOT / "testdata" / "bottle.jpg",
        PROJECT_ROOT / "testdata" / "bus.jpg",
        ROOT / ".venv" / "Lib" / "site-packages" / "ultralytics" / "assets" / "bus.jpg",
    ):
        if candidate.is_file():
            return candidate
    raise FileNotFoundError("no sample image; put testdata/bottle.jpg or testdata/bus.jpg")


def main() -> int:
    parser = argparse.ArgumentParser(description="Step 4 vision pipeline check")
    parser.add_argument("--vision-url", default=os.getenv("VISION_URL", "http://localhost:8082"))
    parser.add_argument("--vision-api-key", default=os.getenv("VISION_API_KEY", "dev-vision-key-change-me"))
    parser.add_argument("--minio-endpoint", default=os.getenv("MINIO_ENDPOINT", "http://localhost:9000"))
    parser.add_argument("--minio-access-key", default=os.getenv("MINIO_ACCESS_KEY", "minioadmin"))
    parser.add_argument("--minio-secret-key", default=os.getenv("MINIO_SECRET_KEY", "minioadmin"))
    parser.add_argument("--minio-bucket", default=os.getenv("MINIO_BUCKET", "cabinet-videos"))
    parser.add_argument("--sample-image", default=None)
    args = parser.parse_args()

    sample = resolve_sample_image(args.sample_image)
    tag = uuid.uuid4().hex[:8]
    print(f"sample image: {sample}")

    health = httpx.get(f"{args.vision_url.rstrip('/')}/health", timeout=10.0).json()
    print("health:", json.dumps(health, ensure_ascii=False))
    if not health.get("recognizer_available"):
        print("FAIL: YOLO not loaded; pip install -r requirements-ml.txt and restart vision-service")
        return 1
    if health.get("mock_enabled"):
        print("WARN: MOCK_ENABLED=true on vision-service; need_review behavior is relaxed")

    # 1) upload API
    up = post_upload(args.vision_url, args.vision_api_key, f"STEP4-UP-{tag}", sample)
    print("upload recognize:", json.dumps(up, ensure_ascii=False))
    if up.get("model_version") not in ("yolov8",) and "fusion" not in str(up.get("model_version", "")):
        print(f"FAIL: unexpected model_version={up.get('model_version')}")
        return 1
    if not up.get("detected_classes"):
        print("FAIL: upload path returned no detected_classes")
        return 1

    # 2) minio:// single
    key = f"sim/step4-{tag}.jpg"
    uri = upload_minio(
        sample, key, args.minio_endpoint, args.minio_access_key, args.minio_secret_key, args.minio_bucket
    )
    single = post_recognize(
        args.vision_url,
        args.vision_api_key,
        {"session_id": f"STEP4-MINIO-{tag}", "video_uri": uri},
    )
    print("minio recognize:", json.dumps(single, ensure_ascii=False))
    if single.get("model_version") != "yolov8":
        print(f"FAIL: minio path model_version={single.get('model_version')}")
        return 1

    # 3) multi-camera fusion
    top_key = f"sim/step4-{tag}-top.jpg"
    side_key = f"sim/step4-{tag}-side.jpg"
    top_uri = upload_minio(
        sample, top_key, args.minio_endpoint, args.minio_access_key, args.minio_secret_key, args.minio_bucket
    )
    side_uri = upload_minio(
        sample, side_key, args.minio_endpoint, args.minio_access_key, args.minio_secret_key, args.minio_bucket
    )
    multi = post_recognize(
        args.vision_url,
        args.vision_api_key,
        {
            "session_id": f"STEP4-MULTI-{tag}",
            "video_uri": top_uri,
            "camera_fusion_mode": "MULTI",
            "video_clips": [
                {"camera": "TOP", "videoUri": top_uri, "capturedAt": 1},
                {"camera": "SIDE", "videoUri": side_uri, "capturedAt": 2},
            ],
        },
    )
    print("multi recognize:", json.dumps(multi, ensure_ascii=False))
    mv = str(multi.get("model_version", ""))
    if not mv.startswith("fusion:"):
        print(f"FAIL: multi fusion expected fusion:* model_version, got {mv}")
        return 1

    print("OK step4 vision checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
