"""AI 视觉服务 — 开发 mock + 端侧识别对接占位（无自研 YOLO）。"""

import hmac
import logging
import os

log = logging.getLogger(__name__)

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from app.kafka_worker import start_kafka_worker
from app.recognition.mock_recognizer import get_force_need_review, set_force_need_review
from app.recognition.types import RecognitionOutput, attach_correlation, new_trace_id
from app.recognizer import get_recognizer
from app.storage import OBJECT_STORAGE_ENDPOINT, start_cache_maintenance

API_KEY_HEADER = "X-Internal-Api-Key"
VISION_API_KEY = os.getenv("VISION_API_KEY", "dev-vision-key-change-me")
RECOGNIZER_BACKEND = os.getenv("RECOGNIZER_BACKEND", "mock")
# 上传体积上限（B-8）；可用 VISION_UPLOAD_MAX_BYTES 覆盖
UPLOAD_MAX_BYTES = int(os.getenv("VISION_UPLOAD_MAX_BYTES", str(20 * 1024 * 1024)))
# H69：staging 与 prod 同为安全环境（拒 MOCK/默认 Key、关 /docs）
_SECURE_PROFILES = ("prod", "production", "stage", "staging")
_IS_SECURE_ENV = os.getenv("SPRING_PROFILES_ACTIVE", os.getenv("APP_ENV", "")).lower() in _SECURE_PROFILES \
    or os.getenv("VISION_DISABLE_DOCS", "").lower() in ("1", "true", "yes")

app = FastAPI(
    title="AI Cabinet Vision Service",
    version="0.9.0",
    docs_url=None if _IS_SECURE_ENV else "/docs",
    redoc_url=None if _IS_SECURE_ENV else "/redoc",
    openapi_url=None if _IS_SECURE_ENV else "/openapi.json",
)
recognizer = get_recognizer()
start_kafka_worker(recognizer)
start_cache_maintenance()

MOCK_ENABLED = os.getenv("MOCK_ENABLED", "true").lower() == "true"
VISION_FORCE_REAL = os.getenv("VISION_FORCE_REAL", "false").lower() == "true"
DEV_VISION_KEY = "dev-vision-key-change-me"
if _IS_SECURE_ENV and MOCK_ENABLED:
    raise RuntimeError("secure env (prod/staging) forbids MOCK_ENABLED=true")
if _IS_SECURE_ENV and VISION_API_KEY == DEV_VISION_KEY:
    raise RuntimeError("secure env (prod/staging) forbids default VISION_API_KEY")
if VISION_API_KEY == DEV_VISION_KEY and not MOCK_ENABLED:
    raise RuntimeError("MOCK_ENABLED=false requires a strong VISION_API_KEY (not dev default)")
if (not MOCK_ENABLED or VISION_FORCE_REAL) and not getattr(recognizer, "available", False):
    raise RuntimeError(
        "Recognizer unavailable while mock is disabled "
        f"(backend={RECOGNIZER_BACKEND}, load_error={getattr(recognizer, 'load_error', None)}). "
        "Fix the recognition backend or set MOCK_ENABLED=true for non-production."
    )

print("=" * 60)
print("vision-service started")
print(f"  backend       = {RECOGNIZER_BACKEND}")
print(f"  recognizer_ok = {getattr(recognizer, 'available', False)}")
print(f"  model_version = {getattr(recognizer, 'model_version', 'n/a')}")
print(f"  load_error    = {getattr(recognizer, 'load_error', None)}")
print(f"  force_real    = {VISION_FORCE_REAL}")
print(f"  storage       = {OBJECT_STORAGE_ENDPOINT}")
print(f"  health        = http://localhost:8082/health")
print("=" * 60)


def _api_key_ok(provided: str | None) -> bool:
    if not VISION_API_KEY or not provided:
        return False
    return hmac.compare_digest(provided, VISION_API_KEY)


# ---- H52：来源 CIDR 校验（与 API Key 同时生效，AND） -------------------------------
# VISION_ALLOWED_CIDRS：逗号分隔 CIDR；空=不限制（保持旧行为兼容）
# VISION_TRUST_PROXY：仅当确有可信反代时置 true，才读取 X-Forwarded-For / X-Real-IP
from app.ip_guard import addr_in_networks, parse_cidrs, parse_ip

VISION_ALLOWED_CIDRS_RAW = os.getenv("VISION_ALLOWED_CIDRS", "")
VISION_ALLOWED_NETWORKS = parse_cidrs(VISION_ALLOWED_CIDRS_RAW)
VISION_TRUST_PROXY = os.getenv("VISION_TRUST_PROXY", "false").lower() == "true"
if VISION_ALLOWED_CIDRS_RAW.strip() and not VISION_ALLOWED_NETWORKS:
    log.warning("VISION_ALLOWED_CIDRS set but no valid entry parsed; CIDR check disabled")


def _client_addr(request: Request):
    if VISION_TRUST_PROXY:
        xff = request.headers.get("X-Forwarded-For", "")
        first = xff.split(",")[0].strip() if xff else ""
        addr = parse_ip(first) if first else None
        if addr is None:
            addr = parse_ip(request.headers.get("X-Real-IP", ""))
        if addr is not None:
            return addr
    host = request.client.host if request.client else ""
    return parse_ip(host or "")


def _cidr_ok(request: Request) -> bool:
    if not VISION_ALLOWED_NETWORKS:
        return True
    return addr_in_networks(_client_addr(request), VISION_ALLOWED_NETWORKS)


@app.middleware("http")
async def verify_api_key(request: Request, call_next):
    path = request.url.path
    if path.startswith("/api/"):
        if not _cidr_ok(request):
            return JSONResponse(status_code=403, content={"detail": "forbidden"})
        provided = request.headers.get(API_KEY_HEADER)
        if not _api_key_ok(provided):
            return JSONResponse(status_code=401, content={"detail": "unauthorized"})
    return await call_next(request)


class LineItem(BaseModel):
    sku_id: str
    quantity: int = Field(ge=1)
    confidence: float = Field(ge=0, le=1)
    source: str = "VISION"


class RecognizeRequest(BaseModel):
    session_id: str
    video_uri: str | None = None
    video_clips: list[dict] | None = None
    camera_fusion_mode: str | None = None
    device_id: str | None = None
    recognition_mode: str | None = None


class VideoClip(BaseModel):
    camera: str
    video_uri: str
    captured_at: int | None = None


class RecognizeResponse(BaseModel):
    task_id: str
    session_id: str
    items: list[LineItem]
    overall_confidence: float
    model_version: str
    need_review: bool = False
    video_uri: str | None = None
    detected_classes: list[str] | None = None
    trace_id: str | None = None


def _to_response(session_id: str, video_uri: str | None, out) -> RecognizeResponse:
    stamped = attach_correlation(out, session_id)
    return RecognizeResponse(
        task_id=f"T-{session_id}",
        session_id=session_id,
        items=[
            LineItem(sku_id=i.sku_id, quantity=i.quantity, confidence=i.confidence)
            for i in stamped.items
        ],
        overall_confidence=stamped.overall_confidence,
        model_version=stamped.model_version,
        need_review=stamped.need_review,
        video_uri=video_uri,
        detected_classes=stamped.detected_classes,
        trace_id=stamped.trace_id,
    )


@app.get("/health")
def health():
    # B-7：对外仅返回存活状态，避免泄露部署细节
    return {"status": "ok"}


@app.get("/health/detail")
def health_detail(request: Request):
    """运维详情：需内部 API Key（与 /api/* 同级）。"""
    provided = request.headers.get(API_KEY_HEADER)
    if not _api_key_ok(provided):
        return JSONResponse(status_code=401, content={"detail": "unauthorized"})
    deepseek_key = os.getenv("DEEPSEEK_API_KEY", "")
    return {
        "status": "ok",
        "recognizer_backend": RECOGNIZER_BACKEND,
        "recognizer_available": getattr(recognizer, "available", False),
        "model_version": getattr(recognizer, "model_version", "mock-dev"),
        "object_storage_endpoint": OBJECT_STORAGE_ENDPOINT,
        "video_cache_dir": os.getenv("VIDEO_CACHE_DIR", "cache/videos"),
        "kafka_enabled": os.getenv("KAFKA_ENABLED", "false").lower() == "true",
        "mock_enabled": os.getenv("MOCK_ENABLED", "true").lower() == "true",
        "mock_force_need_review": get_force_need_review(),
        "vision_force_real": VISION_FORCE_REAL,
        "edge_recognition": "external",
        "load_error": getattr(recognizer, "load_error", None),
        "deepseek_configured": bool(deepseek_key),
        "deepseek_model": os.getenv("DEEPSEEK_MODEL", "deepseek-v4-flash"),
        "deepseek_auto_charge": os.getenv("DEEPSEEK_AUTO_CHARGE", "false").lower() == "true",
        "deepseek_timeout_ms": int(os.getenv("DEEPSEEK_TIMEOUT_MS", "2000")),
    }


class ForceNeedReviewRequest(BaseModel):
    enabled: bool


@app.post("/api/v2/vision/debug/force-need-review")
def debug_force_need_review(req: ForceNeedReviewRequest):
    """Local/E2E helper: toggle mock need_review without recreating the container."""
    if _IS_SECURE_ENV or not MOCK_ENABLED:
        raise HTTPException(status_code=403, detail="debug endpoint disabled")
    enabled = set_force_need_review(req.enabled)
    return {"ok": True, "mock_force_need_review": enabled}


RECOGNIZE_TIMEOUT_MS = int(os.getenv("RECOGNIZE_TIMEOUT_MS", "30000"))


def _empty_need_review(session_id: str, reason: str) -> RecognitionOutput:
    return attach_correlation(
        RecognitionOutput(
            items=[],
            overall_confidence=0.0,
            model_version=reason,
            need_review=True,
            detected_classes=[reason],
        ),
        session_id,
        new_trace_id(),
    )


def _run_recognize(
    session_id: str,
    video_uri: str | None,
    device_id: str | None,
    recognition_mode: str | None,
) -> RecognitionOutput:
    try:
        from concurrent.futures import ThreadPoolExecutor, TimeoutError as FuturesTimeout

        with ThreadPoolExecutor(max_workers=1) as pool:
            fut = pool.submit(
                recognizer.recognize,
                session_id,
                video_uri,
                device_id,
                recognition_mode=recognition_mode or None,
            )
            out = fut.result(timeout=max(1, RECOGNIZE_TIMEOUT_MS) / 1000.0)
            return attach_correlation(out, session_id)
    except FuturesTimeout:
        log.warning("recognize timeout session=%s ms=%s", session_id, RECOGNIZE_TIMEOUT_MS)
        return _empty_need_review(session_id, "recognize-timeout")
    except Exception:
        log.exception("recognize failed session=%s", session_id)
        return _empty_need_review(session_id, "recognize-error")


@app.post("/api/v2/vision/recognize", response_model=RecognizeResponse)
def recognize(req: RecognizeRequest):
    fusion_mode = (req.camera_fusion_mode or "SINGLE").upper()
    clips = req.video_clips or []
    mode = (req.recognition_mode or "").upper()

    if fusion_mode == "MULTI" and len(clips) >= 2:
        from app.recognition.fusion import fuse_outputs

        outputs = []
        for clip in clips:
            uri = clip.get("videoUri") or clip.get("video_uri")
            if not uri:
                continue
            cam = clip.get("camera", "?")
            sid = f"{req.session_id}:{cam}"
            outputs.append(_run_recognize(sid, uri, req.device_id, mode or None))
        out = fuse_outputs(outputs, fusion_mode)
        return _to_response(req.session_id, req.video_uri, out)

    if fusion_mode == "MULTI" and len(clips) == 1:
        uri = clips[0].get("videoUri") or clips[0].get("video_uri")
        out = _run_recognize(req.session_id, uri or req.video_uri, req.device_id, mode or None)
        return _to_response(req.session_id, uri or req.video_uri, out)

    out = _run_recognize(req.session_id, req.video_uri, req.device_id, mode or None)
    return _to_response(req.session_id, req.video_uri, out)


async def _read_upload_limited(file: UploadFile, max_bytes: int = UPLOAD_MAX_BYTES) -> bytes:
    """流式读取并限制体积，超限 413（B-8）。"""
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = await file.read(1024 * 1024)
        if not chunk:
            break
        total += len(chunk)
        if total > max_bytes:
            raise HTTPException(
                status_code=413,
                detail=f"upload too large (max {max_bytes} bytes)",
            )
        chunks.append(chunk)
    return b"".join(chunks)


@app.post("/api/v2/vision/recognize/upload", response_model=RecognizeResponse)
async def recognize_upload(
    session_id: str = Form("TEST-UPLOAD"),
    device_id: str = Form(""),
    file: UploadFile = File(...),
):
    data = await _read_upload_limited(file)
    filename = file.filename or "image.jpg"
    upload = recognizer.recognize_upload
    try:
        try:
            out = upload(session_id, data, filename, device_id=device_id or None)  # type: ignore[call-arg]
        except TypeError:
            out = upload(session_id, data, filename)
        out = attach_correlation(out, session_id)
    except Exception:
        log.exception("recognize_upload failed session=%s", session_id)
        out = _empty_need_review(session_id, "upload-error")
    return _to_response(session_id, f"upload://{file.filename}", out)


@app.post("/api/v2/vision/suggest-class")
async def suggest_class(
    sku_name: str = Form(""),
    file: UploadFile = File(...),
):
    from app.recognition.deepseek_recognizer import DeepSeekRecognizer

    data = await _read_upload_limited(file)
    rec = DeepSeekRecognizer()
    result = rec.suggest_class_from_image(data, sku_name or None)
    return result


@app.post("/api/v2/vision/dispute-suggest")
async def dispute_suggest(
    device_id: str = Form(""),
    file: UploadFile = File(...),
):
    from app.recognition.deepseek_recognizer import DeepSeekRecognizer

    data = await _read_upload_limited(file)
    rec = DeepSeekRecognizer()
    out = rec.suggest_dispute_skus(data, device_id or None)
    return _to_response("dispute-suggest", f"upload://{file.filename}", out)


@app.post("/api/v2/vision/recognize/async")
def recognize_async(req: RecognizeRequest):
    return {"task_id": f"T-{req.session_id}", "status": "PENDING", "video_uri": req.video_uri}


@app.get("/api/v2/vision/tasks/{task_id}")
def get_task(task_id: str):
    return {"task_id": task_id, "status": "COMPLETED", "items": []}


if __name__ == "__main__":
    import uvicorn

    port = int(os.getenv("PORT", "8082"))
    uvicorn.run(app, host="0.0.0.0", port=port)
