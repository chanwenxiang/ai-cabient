"""delta 识别单元测试。"""

from app.recognition.delta_recognizer import compute_delta


def test_taken_one_bottle():
    open_counts = {"SKU-DEMO-001": (2, 0.9)}
    close_counts = {"SKU-DEMO-001": (1, 0.88)}
    out = compute_delta(
        open_counts,
        close_counts,
        model_version="yolov8",
        detected_classes=["bottle"],
    )
    assert len(out.items) == 1
    assert out.items[0].sku_id == "SKU-DEMO-001"
    assert out.items[0].quantity == 1
    assert out.need_review is False


def test_put_back_triggers_review():
    open_counts = {"SKU-DEMO-001": (1, 0.9)}
    close_counts = {"SKU-DEMO-001": (2, 0.88)}
    out = compute_delta(
        open_counts,
        close_counts,
        model_version="yolov8",
        detected_classes=["bottle"],
    )
    assert out.items == []
    assert out.need_review is True


def test_no_change_empty_items():
    counts = {"SKU-DEMO-001": (1, 0.9)}
    out = compute_delta(counts, counts, model_version="yolov8", detected_classes=["bottle"])
    assert out.items == []
    assert out.need_review is True
