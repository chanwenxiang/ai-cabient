"""开门前后帧 SKU 差异结算。"""

from __future__ import annotations

from app.recognition.types import RecognizedItem, RecognitionOutput


def compute_delta(
    open_counts: dict[str, tuple[int, float]],
    close_counts: dict[str, tuple[int, float]],
    *,
    model_version: str,
    detected_classes: list[str],
) -> RecognitionOutput:
    """taken_qty = open_count - close_count；close > open 视为放回，需人工审核。"""
    all_skus = set(open_counts) | set(close_counts)
    items: list[RecognizedItem] = []
    conf_values: list[float] = []
    need_review = False

    for sku in sorted(all_skus):
        open_qty, open_conf = open_counts.get(sku, (0, 0.0))
        close_qty, close_conf = close_counts.get(sku, (0, 0.0))
        taken = open_qty - close_qty
        if close_qty > open_qty:
            need_review = True
        if taken <= 0:
            continue
        conf = (open_conf + close_conf) / 2 if close_conf else open_conf
        items.append(RecognizedItem(sku_id=sku, quantity=taken, confidence=min(0.99, conf or 0.5)))
        conf_values.append(conf or 0.5)

    overall = sum(conf_values) / len(conf_values) if conf_values else 0.0
    if not items:
        need_review = True

    return RecognitionOutput(
        items=items,
        overall_confidence=round(overall, 3),
        model_version=f"{model_version}-delta",
        need_review=need_review,
        detected_classes=sorted(set(detected_classes)),
    )
