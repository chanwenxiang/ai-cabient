"""fusion / types 关联字段单测（V-P2-5）。"""

from app.recognition.fusion import fuse_outputs
from app.recognition.types import RecognizedItem, RecognitionOutput, attach_correlation


def _out(
    items: list[tuple[str, int, float]],
    *,
    model: str = "m1",
    need_review: bool = False,
    classes: list[str] | None = None,
    session_id: str | None = None,
    trace_id: str | None = None,
) -> RecognitionOutput:
    return RecognitionOutput(
        items=[RecognizedItem(sku_id=s, quantity=q, confidence=c) for s, q, c in items],
        overall_confidence=0.5,
        model_version=model,
        need_review=need_review,
        detected_classes=classes,
        session_id=session_id,
        trace_id=trace_id,
    )


def test_fuse_empty():
    out = fuse_outputs([])
    assert out.need_review is True
    assert out.model_version == "fusion-empty"
    assert out.items == []


def test_fuse_single_passthrough():
    one = _out([("SKU-A", 2, 0.9)], session_id="S1", trace_id="t1")
    assert fuse_outputs([one]) is one


def test_fuse_multi_takes_max_qty_and_avg_conf():
    a = _out([("SKU-A", 1, 0.8), ("SKU-B", 1, 0.6)], model="cam-a", classes=["a"])
    b = _out([("SKU-A", 3, 1.0)], model="cam-b", classes=["b"], need_review=True)
    out = fuse_outputs([a, b], fusion_mode="MULTI")
    by_sku = {i.sku_id: i for i in out.items}
    assert by_sku["SKU-A"].quantity == 3
    assert by_sku["SKU-A"].confidence == 0.9
    assert by_sku["SKU-B"].quantity == 1
    assert out.need_review is True
    assert out.detected_classes == ["a", "b"]
    assert out.model_version.startswith("fusion:")


def test_fuse_sum_mode():
    a = _out([("SKU-A", 2, 0.5)], model="x")
    b = _out([("SKU-A", 3, 0.5)], model="y")
    out = fuse_outputs([a, b], fusion_mode="SUM")
    assert out.items[0].quantity == 5


def test_fuse_preserves_correlation():
    a = _out([("SKU-A", 1, 0.9)], session_id="SESS", trace_id="trace-a")
    b = _out([("SKU-A", 2, 0.7)])
    out = fuse_outputs([a, b])
    assert out.session_id == "SESS"
    assert out.trace_id == "trace-a"


def test_attach_correlation_fills_missing():
    raw = _out([])
    stamped = attach_correlation(raw, "S9", "fixed-trace")
    assert stamped.session_id == "S9"
    assert stamped.trace_id == "fixed-trace"
    again = attach_correlation(stamped, "OTHER", "other-trace")
    assert again.session_id == "S9"
    assert again.trace_id == "fixed-trace"
