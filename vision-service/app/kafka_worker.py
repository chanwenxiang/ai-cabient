"""Kafka 异步识别 worker（可选）。"""

from __future__ import annotations

import json
import logging
import os
import threading
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FuturesTimeout

log = logging.getLogger(__name__)

REQUEST_TOPIC = "aicabinet.vision.recognize.request"
RESULT_TOPIC = "aicabinet.vision.recognize.result"
REQUEST_DLT_TOPIC = os.getenv(
    "KAFKA_VISION_REQUEST_DLT", "aicabinet.vision.recognize.request.DLT"
)
BOOTSTRAP = os.getenv("KAFKA_BOOTSTRAP", "localhost:9092")
RECOGNIZE_TIMEOUT_MS = int(os.getenv("RECOGNIZE_TIMEOUT_MS", "30000"))
MAX_POLL_RECORDS = int(os.getenv("KAFKA_MAX_POLL_RECORDS", "20"))
MAX_POLL_INTERVAL_MS = int(os.getenv("KAFKA_MAX_POLL_INTERVAL_MS", "300000"))
SESSION_TIMEOUT_MS = int(os.getenv("KAFKA_SESSION_TIMEOUT_MS", "45000"))


def _need_review_payload(session_id: str, task_id: str, reason: str) -> dict:
    return {
        "sessionId": session_id,
        "taskId": task_id,
        "overallConfidence": 0.0,
        "needReview": True,
        "items": [],
        "modelVersion": reason,
        "detectedClasses": [reason],
    }


def _recognize(recognizer, req: dict):
    session_id = req["sessionId"]
    video_uri = req.get("videoUri") or None
    clips = req.get("videoClips") or []
    fusion_mode = (req.get("cameraFusionMode") or "SINGLE").upper()
    recognition_mode = (req.get("recognitionMode") or "").upper() or None
    device_id = req.get("deviceId")

    def _one(sid: str, uri: str | None):
        return recognizer.recognize(
            sid, uri, device_id, recognition_mode=recognition_mode
        )

    if fusion_mode == "MULTI" and len(clips) >= 2:
        from app.recognition.fusion import fuse_outputs

        outputs = []
        for clip in clips:
            uri = clip.get("videoUri") or clip.get("video_uri")
            if not uri:
                continue
            cam = clip.get("camera", "?")
            outputs.append(_one(f"{session_id}:{cam}", uri))
        return fuse_outputs(outputs, fusion_mode)

    return _one(session_id, video_uri)


def _recognize_with_timeout(recognizer, req: dict):
    timeout_s = max(1, RECOGNIZE_TIMEOUT_MS) / 1000.0
    with ThreadPoolExecutor(max_workers=1) as pool:
        fut = pool.submit(_recognize, recognizer, req)
        return fut.result(timeout=timeout_s)


def start_kafka_worker(recognizer) -> threading.Thread | None:
    if os.getenv("KAFKA_ENABLED", "false").lower() != "true":
        return None

    def run() -> None:
        try:
            from kafka import KafkaConsumer, KafkaProducer  # type: ignore
        except ImportError:
            log.error("kafka-python not installed, worker disabled")
            return

        def _decode(raw: bytes | None) -> str:
            return raw.decode("utf-8") if raw else ""

        def _encode(value: object) -> bytes:
            if isinstance(value, bytes):
                return value
            return str(value).encode("utf-8")

        consumer = KafkaConsumer(
            REQUEST_TOPIC,
            bootstrap_servers=BOOTSTRAP,
            group_id="vision-service",
            auto_offset_reset="earliest",
            enable_auto_commit=False,
            max_poll_records=max(1, MAX_POLL_RECORDS),
            max_poll_interval_ms=max(1000, MAX_POLL_INTERVAL_MS),
            session_timeout_ms=max(1000, SESSION_TIMEOUT_MS),
            value_deserializer=_decode,
        )
        producer = KafkaProducer(
            bootstrap_servers=BOOTSTRAP,
            value_serializer=_encode,
        )
        log.info(
            "kafka worker started bootstrap=%s request=%s result=%s dlt=%s timeoutMs=%s maxPollRecords=%s",
            BOOTSTRAP,
            REQUEST_TOPIC,
            RESULT_TOPIC,
            REQUEST_DLT_TOPIC,
            RECOGNIZE_TIMEOUT_MS,
            MAX_POLL_RECORDS,
        )

        for message in consumer:
            raw_value = message.value
            session_id = "?"
            task_id = "?"
            try:
                req = json.loads(raw_value)
                session_id = req["sessionId"]
                task_id = req.get("taskId") or f"T-{session_id}"
                try:
                    out = _recognize_with_timeout(recognizer, req)
                    result = {
                        "sessionId": session_id,
                        "taskId": task_id,
                        "overallConfidence": out.overall_confidence,
                        "needReview": out.need_review,
                        "items": [
                            {
                                "skuId": i.sku_id,
                                "quantity": i.quantity,
                                "confidence": i.confidence,
                            }
                            for i in out.items
                        ],
                    }
                except FuturesTimeout:
                    log.warning(
                        "kafka recognize timeout session=%s ms=%s",
                        session_id,
                        RECOGNIZE_TIMEOUT_MS,
                    )
                    result = _need_review_payload(
                        session_id, task_id, "recognize-timeout"
                    )
                except Exception as recognize_exc:
                    log.exception(
                        "kafka recognize failed session=%s: %s",
                        session_id,
                        recognize_exc,
                    )
                    result = _need_review_payload(
                        session_id, task_id, "recognize-error"
                    )

                producer.send(RESULT_TOPIC, json.dumps(result))
                producer.flush()
                consumer.commit()
                log.info(
                    "vision result published session=%s needReview=%s",
                    session_id,
                    result.get("needReview"),
                )
            except Exception as exc:
                log.exception("kafka worker failed: %s", exc)
                try:
                    dlt_payload = {
                        "error": str(exc),
                        "sessionId": session_id,
                        "taskId": task_id,
                        "raw": raw_value if isinstance(raw_value, str) else str(raw_value),
                    }
                    producer.send(REQUEST_DLT_TOPIC, json.dumps(dlt_payload, ensure_ascii=False))
                    producer.flush()
                except Exception as dlt_exc:
                    log.exception("failed to publish vision request DLT: %s", dlt_exc)
                try:
                    consumer.commit()
                except Exception as commit_exc:
                    log.exception("failed to commit after DLT: %s", commit_exc)

    thread = threading.Thread(target=run, name="vision-kafka-worker", daemon=True)
    thread.start()
    return thread
