"""AI 视觉识别服务 — 自训 YOLO delta + class→SKU 映射。"""

import logging
import os

log = logging.getLogger(__name__)

from fastapi import FastAPI, File, Form, Request, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from app.kafka_worker import start_kafka_worker
from app.recognizer import get_recognizer
from app.storage import OBJECT_STORAGE_ENDPOINT

API_KEY_HEADER = "X-Internal-Api-Key"
VISION_API_KEY = os.getenv("VISION_API_KEY", "dev-vision-key-change-me")
RECOGNIZER_BACKEND = os.getenv("RECOGNIZER_BACKEND", "yolo")

app = FastAPI(title="AI Cabinet Vision Service", version="0.8.0")
recognizer = get_recognizer()
start_kafka_worker(recognizer)

MOCK_ENABLED = os.getenv("MOCK_ENABLED", "true").lower() == "true"
VISION_FORCE_REAL = os.getenv("VISION_FORCE_REAL", "false").lower() == "true"
YOLO_RECOGNITION_MODE = os.getenv("YOLO_RECOGNITION_MODE", "delta")
DEV_VISION_KEY = "dev-vision-key-change-me"
if VISION_API_KEY == DEV_VISION_KEY and not MOCK_ENABLED:
    raise RuntimeError("MOCK_ENABLED=false requires a strong VISION_API_KEY (not dev default)")
if (not MOCK_ENABLED or VISION_FORCE_REAL) and not getattr(recognizer, "available", False):
    err = getattr(recognizer, "load_error", "yolo not loaded")
    raise RuntimeError(f"Real YOLO recognition requires loaded model: {err}")

print("=" * 60)
print("vision-service started")
print(f"  backend       = {RECOGNIZER_BACKEND}")
print(f"  yolo_loaded   = {getattr(recognizer, 'available', False)}")
print(f"  model_version = {getattr(recognizer, 'model_version', 'n/a')}")
print(f"  model_path    = {getattr(recognizer, 'model_path', 'n/a')}")
print(f"  load_error    = {getattr(recognizer, 'load_error', None)}")
print(f"  force_real    = {VISION_FORCE_REAL}")
print(f"  yolo_mode     = {YOLO_RECOGNITION_MODE}")
print(f"  storage       = {OBJECT_STORAGE_ENDPOINT}")
print(f"  health        = http://localhost:8082/health")
print("=" * 60)


@app.middleware("http")
async def verify_api_key(request: Request, call_next):
    path = request.url.path
    if path.startswith("/api/"):
        provided = request.headers.get(API_KEY_HEADER)
        if not VISION_API_KEY or provided != VISION_API_KEY:
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


def _to_response(session_id: str, video_uri: str | None, out) -> RecognizeResponse:
    return RecognizeResponse(
        task_id=f"T-{session_id}",
        session_id=session_id,
        items=[
            LineItem(sku_id=i.sku_id, quantity=i.quantity, confidence=i.confidence)
            for i in out.items
        ],
        overall_confidence=out.overall_confidence,
        model_version=out.model_version,
        need_review=out.need_review,
        video_uri=video_uri,
        detected_classes=out.detected_classes,
    )


@app.get("/health")
def health():
    deepseek_key = os.getenv("DEEPSEEK_API_KEY", "")
    return {
        "status": "ok",
        "recognizer_backend": RECOGNIZER_BACKEND,
        "recognizer_available": getattr(recognizer, "available", False),
        "model_version": getattr(recognizer, "model_version", getattr(recognizer, "model_path", "unknown")),
        "model_path": getattr(recognizer, "model_path", "unknown"),
        "object_storage_endpoint": OBJECT_STORAGE_ENDPOINT,
        "video_cache_dir": os.getenv("VIDEO_CACHE_DIR", "cache/videos"),
        "kafka_enabled": os.getenv("KAFKA_ENABLED", "false").lower() == "true",
        "mock_enabled": os.getenv("MOCK_ENABLED", "true").lower() == "true",
        "vision_force_real": VISION_FORCE_REAL,
        "yolo_recognition_mode": YOLO_RECOGNITION_MODE,
        "yolo_loaded": getattr(recognizer, "available", False),
        "load_error": getattr(recognizer, "load_error", None),
        "deepseek_configured": bool(deepseek_key),
        "deepseek_model": os.getenv("DEEPSEEK_MODEL", "deepseek-v4-flash"),
        "deepseek_auto_charge": os.getenv("DEEPSEEK_AUTO_CHARGE", "false").lower() == "true",
        "deepseek_timeout_ms": int(os.getenv("DEEPSEEK_TIMEOUT_MS", "2000")),
    }


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
            outputs.append(
                recognizer.recognize(sid, uri, req.device_id, recognition_mode=mode or None)
            )
        out = fuse_outputs(outputs, fusion_mode)
        return _to_response(req.session_id, req.video_uri, out)

    if fusion_mode == "MULTI" and len(clips) == 1:
        uri = clips[0].get("videoUri") or clips[0].get("video_uri")
        out = recognizer.recognize(
            req.session_id, uri or req.video_uri, req.device_id, recognition_mode=mode or None
        )
        return _to_response(req.session_id, uri or req.video_uri, out)

    out = recognizer.recognize(
        req.session_id, req.video_uri, req.device_id, recognition_mode=mode or None
    )
    return _to_response(req.session_id, req.video_uri, out)


@app.post("/api/v2/vision/recognize/upload", response_model=RecognizeResponse)
async def recognize_upload(
    session_id: str = Form("TEST-UPLOAD"),
    device_id: str = Form(""),
    file: UploadFile = File(...),
):
    data = await file.read()
    filename = file.filename or "image.jpg"
    # yolo_deepseek / deepseek 支持 device_id；纯 yolo 忽略多余参数
    upload = recognizer.recognize_upload
    try:
        out = upload(session_id, data, filename, device_id=device_id or None)  # type: ignore[call-arg]
    except TypeError:
        out = upload(session_id, data, filename)
    return _to_response(session_id, f"upload://{file.filename}", out)


@app.post("/api/v2/vision/suggest-class")
async def suggest_class(
    sku_name: str = Form(""),
    file: UploadFile = File(...),
):
    from app.recognition.deepseek_recognizer import DeepSeekRecognizer

    data = await file.read()
    rec = DeepSeekRecognizer()
    result = rec.suggest_class_from_image(data, sku_name or None)
    return result


@app.post("/api/v2/vision/dispute-suggest")
async def dispute_suggest(
    device_id: str = Form(""),
    file: UploadFile = File(...),
):
    from app.recognition.deepseek_recognizer import DeepSeekRecognizer

    data = await file.read()
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
