"""deepseek_recognizer 纯函数与离线分支单测（V-P2-5）。"""

from app.recognition.deepseek_recognizer import (
    DeepSeekRecognizer,
    _match_skus_from_ocr,
    _parse_json_blob,
    _slugify,
)


def test_parse_json_blob_array_and_think_strip():
    raw = "<think>ignore</think>\n[{\"sku_id\":\"A\",\"quantity\":1,\"confidence\":0.9}]"
    parsed = _parse_json_blob(raw)
    assert isinstance(parsed, list)
    assert parsed[0]["sku_id"] == "A"


def test_parse_json_blob_embedded_object():
    parsed = _parse_json_blob('note {"yolo_class_name":"cola","confidence":0.8} ok')
    assert parsed["yolo_class_name"] == "cola"


def test_match_skus_from_ocr_name_and_brand():
    ctx = [
        {"skuId": "SKU-1", "skuName": "东方树叶茉莉花茶 500ml"},
        {"skuId": "SKU-2", "skuName": "可口可乐"},
    ]
    hits = _match_skus_from_ocr("包装文字 东方树叶 茉莉花茶", ctx)
    ids = {h.sku_id for h in hits}
    assert "SKU-1" in ids
    # 谐音纠错：莱莉 → 茉莉
    hits2 = _match_skus_from_ocr("莱莉花茶", [{"skuId": "SKU-1", "skuName": "茉莉花茶"}])
    assert hits2 and hits2[0].sku_id == "SKU-1"


def test_slugify():
    assert _slugify("Coca Cola!") == "coca_cola"
    assert _slugify("") == "sku_unknown"


def test_deepseek_unavailable_without_api_key(monkeypatch):
    monkeypatch.setattr(
        "app.recognition.deepseek_recognizer.DEEPSEEK_API_KEY",
        "",
    )
    rec = DeepSeekRecognizer()
    assert rec.available is False
    out = rec.recognize_upload("S1", b"\xff\xd8\xff", "x.jpg")
    assert out.need_review is True
    assert out.items == []
    assert out.model_version == "deepseek-unavailable"


def test_deepseek_recognize_no_frames(monkeypatch):
    monkeypatch.setattr(
        "app.recognition.deepseek_recognizer.DEEPSEEK_API_KEY",
        "test-key",
    )
    monkeypatch.setattr(
        DeepSeekRecognizer,
        "_load_frames",
        lambda self, uri: [],
    )
    rec = DeepSeekRecognizer()
    out = rec.recognize("S2", None)
    assert out.need_review is True
    assert "no frames" in (out.detected_classes or [""])[0]


def test_deepseek_call_chat_uses_timeout(monkeypatch):
    monkeypatch.setattr(
        "app.recognition.deepseek_recognizer.DEEPSEEK_API_KEY",
        "test-key",
    )
    monkeypatch.setattr(
        "app.recognition.deepseek_recognizer.DEEPSEEK_TIMEOUT_MS",
        1234,
    )
    seen = {}

    class FakeResp:
        def raise_for_status(self):
            return None

        def json(self):
            return {"choices": [{"message": {"content": "[]"}}]}

    class FakeClient:
        def __init__(self, timeout):
            seen["timeout"] = timeout

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

        def post(self, *args, **kwargs):
            return FakeResp()

    monkeypatch.setattr(
        "app.recognition.deepseek_recognizer.httpx.Client",
        FakeClient,
    )
    rec = DeepSeekRecognizer()
    out = rec._call_chat("hello")
    assert seen["timeout"] == 1.234
    assert out["parsed"] == []
