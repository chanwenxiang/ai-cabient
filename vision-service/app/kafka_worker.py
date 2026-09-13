"""Kafka 异步识别 worker（可选）。"""

from __future__ import annotations

import json
import logging
import os
import threading

log = logging.getLogger(__name__)

REQUEST_TOPIC = "aicabinet.vision.recognize.request"
RESULT_TOPIC = "aicabinet.vision.recognize.result"
REQUEST_DLT_TOPIC = os.getenv(
    "KAFKA_VISION_REQUEST_DLT", "aicabinet.vision.recognize.request.DLT"
)
BOOTSTRAP = os.getenv("KAFKA_BOOTSTRAP", "localhost:9092")


def _recognize(recognizer, req: dict):
    session_id = req["sessionId"]
    video_uri = req.get("videoUri") or None
    clips = req.get("videoClips") or []
    fusion_mode = (req.get("cameraFusionMode") or "SINGLE").upper()

    if fusion_mode == "MULTI" and len(clips) >= 2:
        from app.recognition.fusion import fuse_outputs

        outputs = []
        for clip in clips:
            uri = clip.get("videoUri") or clip.get("video_uri")
            if not uri:
                continue
            cam = clip.get("camera", "?")
            outputs.append(recognizer.recognize(f"{session_id}:{cam}", uri))
        return fuse_outputs(outputs, fusion_mode)

    return recognizer.recognize(session_id, video_uri)


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
            value_deserializer=_decode,
        )
        producer = KafkaProducer(
            bootstrap_servers=BOOTSTRAP,
            value_serializer=_encode,
        )
        log.info(
            "kafka worker started bootstrap=%s request=%s result=%s dlt=%s",
            BOOTSTRAP,
            REQUEST_TOPIC,
            RESULT_TOPIC,
            REQUEST_DLT_TOPIC,
        )

        for message in consumer:
            raw_value = message.value
            try:
                req = json.loads(raw_value)
                session_id = req["sessionId"]
                task_id = req.get("taskId") or f"T-{session_id}"
                out = _recognize(recognizer, req)
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
                producer.send(RESULT_TOPIC, json.dumps(result))
                producer.flush()
                consumer.commit()
                log.info("vision result published session=%s", session_id)
            except Exception as exc:
                log.exception("kafka worker failed: %s", exc)
                try:
                    dlt_payload = {
                        "error": str(exc),
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
