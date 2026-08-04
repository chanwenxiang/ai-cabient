from __future__ import annotations

from dataclasses import dataclass


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
