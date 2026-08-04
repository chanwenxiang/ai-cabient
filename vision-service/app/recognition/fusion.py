"""多摄像头识别结果融合。"""

from __future__ import annotations

from app.recognition.types import RecognizedItem, RecognitionOutput


def fuse_outputs(outputs: list[RecognitionOutput], fusion_mode: str = "MULTI") -> RecognitionOutput:
    """合并多路识别：同 SKU 取各摄像头数量最大值，置信度取均值。"""
    if not outputs:
        return RecognitionOutput(
            items=[],
            overall_confidence=0.0,
            model_version="fusion-empty",
            need_review=True,
        )
    if len(outputs) == 1:
        return outputs[0]

    merged_qty: dict[str, list[int]] = {}
    merged_conf: dict[str, list[float]] = {}
    detected: set[str] = set()
    model_versions: list[str] = []
    need_review = False

    for out in outputs:
        model_versions.append(out.model_version)
        if out.need_review:
            need_review = True
        if out.detected_classes:
            detected.update(out.detected_classes)
        for item in out.items:
            merged_qty.setdefault(item.sku_id, []).append(item.quantity)
            merged_conf.setdefault(item.sku_id, []).append(item.confidence)

    items: list[RecognizedItem] = []
    conf_values: list[float] = []
    for sku_id, qty_list in merged_qty.items():
        qty = max(qty_list) if fusion_mode.upper() == "MULTI" else sum(qty_list)
        confs = merged_conf.get(sku_id, [0.5])
        conf = sum(confs) / len(confs)
        conf_values.append(conf)
        items.append(RecognizedItem(sku_id=sku_id, quantity=max(1, qty), confidence=round(conf, 3)))

    overall = sum(conf_values) / len(conf_values) if conf_values else 0.0
    if not items:
        need_review = True

    return RecognitionOutput(
        items=items,
        overall_confidence=round(overall, 3),
        model_version="fusion:" + "+".join(sorted(set(model_versions))),
        need_review=need_review,
        detected_classes=sorted(detected) if detected else None,
    )
