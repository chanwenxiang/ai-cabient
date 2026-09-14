from __future__ import annotations

import uuid
from dataclasses import dataclass, replace


@dataclass
class RecognizedItem:
    sku_id: str
    quantity: int
    confidence: float


@dataclass
class RecognitionOutput:
    items: list[RecognizedItem]
    overall_confidence: float
    model_version: str
    need_review: bool
    detected_classes: list[str] | None = None
    # V-P2-3：关联字段，便于日志/Kafka/HTTP 响应串联排查
    session_id: str | None = None
    trace_id: str | None = None


def new_trace_id() -> str:
    return uuid.uuid4().hex[:16]


def attach_correlation(
    out: RecognitionOutput,
    session_id: str | None,
    trace_id: str | None = None,
) -> RecognitionOutput:
    """为识别结果补齐 session_id / trace_id（已有值不覆盖）。"""
    sid = out.session_id or session_id
    tid = out.trace_id or trace_id or new_trace_id()
    if out.session_id == sid and out.trace_id == tid:
        return out
    return replace(out, session_id=sid, trace_id=tid)
