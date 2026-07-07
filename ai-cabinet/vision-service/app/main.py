"""AI 视觉识别服务 — 支持 YOLO（本地）/ 阿里云商品理解（生产）。"""

import os

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

print("=" * 60)
print("vision-service started")
print(f"  backend       = {RECOGNIZER_BACKEND}")
print(f"  yolo_loaded   = {getattr(recognizer, 'available', False)}")
print(f"  model_path    = {getattr(recognizer, 'model_path', 'n/a')}")
print(f"  load_error    = {getattr(recognizer, 'load_error', None)}")
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
    return {
        "status": "ok",
        "recognizer_backend": RECOGNIZER_BACKEND,
        "recognizer_available": getattr(recognizer, "available", False),
        "model_version": getattr(recognizer, "model_path", "unknown"),
        "object_storage_endpoint": OBJECT_STORAGE_ENDPOINT,
        "video_cache_dir": os.getenv("VIDEO_CACHE_DIR", "cache/videos"),
        "kafka_enabled": os.getenv("KAFKA_ENABLED", "false").lower() == "true",
        "mock_enabled": os.getenv("MOCK_ENABLED", "true").lower() == "true",
        "load_error": getattr(recognizer, "load_error", None),
        "aliyun_configured": bool(os.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID")),
    }


@app.post("/api/v2/vision/recognize", response_model=RecognizeResponse)
def recognize(req: RecognizeRequest):
    fusion_mode = (req.camera_fusion_mode or "SINGLE").upper()
    clips = req.video_clips or []

    if fusion_mode == "MULTI" and len(clips) >= 2:
        from app.recognition.fusion import fuse_outputs

        outputs = []
        for clip in clips:
            uri = clip.get("videoUri") or clip.get("video_uri")
            if not uri:
                continue
            cam = clip.get("camera", "?")
            sid = f"{req.session_id}:{cam}"
            outputs.append(recognizer.recognize(sid, uri))
        out = fuse_outputs(outputs, fusion_mode)
        return _to_response(req.session_id, req.video_uri, out)

    if fusion_mode == "MULTI" and len(clips) == 1:
        uri = clips[0].get("videoUri") or clips[0].get("video_uri")
        out = recognizer.recognize(req.session_id, uri or req.video_uri)
        return _to_response(req.session_id, uri or req.video_uri, out)

    out = recognizer.recognize(req.session_id, req.video_uri)
    return _to_response(req.session_id, req.video_uri, out)


@app.post("/api/v2/vision/recognize/upload", response_model=RecognizeResponse)
async def recognize_upload(
    session_id: str = Form("TEST-UPLOAD"),
    file: UploadFile = File(...),
):
    data = await file.read()
    out = recognizer.recognize_upload(session_id, data, file.filename or "image.jpg")
    return _to_response(session_id, f"upload://{file.filename}", out)


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
