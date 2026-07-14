"""DeepSeek 识别兜底：官方 API 当前仅文本；图片走 OCR + DeepSeek 文本匹配 SKU。"""

from __future__ import annotations

import base64
import json
import logging
import os
import re
from io import BytesIO
from typing import Any

import httpx

from app.recognition.frame_extract import extract_key_frames
from app.recognition.mapping_client import (
    fetch_catalog_classes,
    fetch_device_vision_context,
)
from app.recognition.types import RecognizedItem, RecognitionOutput
from app.storage import resolve_video_path

log = logging.getLogger(__name__)

DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY", "")
DEEPSEEK_BASE_URL = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com").rstrip("/")
DEEPSEEK_MODEL = os.getenv("DEEPSEEK_MODEL", "deepseek-v4-flash")
DEEPSEEK_TIMEOUT_MS = int(os.getenv("DEEPSEEK_TIMEOUT_MS", "15000"))
DEEPSEEK_AUTO_CHARGE = os.getenv("DEEPSEEK_AUTO_CHARGE", "false").lower() == "true"
REVIEW_CONF_THRESHOLD = float(os.getenv("DEEPSEEK_REVIEW_CONF", "0.7"))
# 官方 API 若恢复原生 vision，设 DEEPSEEK_FORCE_VISION=true 重试 image_url
DEEPSEEK_FORCE_VISION = os.getenv("DEEPSEEK_FORCE_VISION", "false").lower() == "true"


class DeepSeekRecognizer:
    """设备 SKU 白名单 + DeepSeek 文本匹配（OCR 读包装）。"""

    def __init__(self) -> None:
        self.load_error: str | None = None
        if not DEEPSEEK_API_KEY:
            self.load_error = "DEEPSEEK_API_KEY not set"

    @property
    def available(self) -> bool:
        return bool(DEEPSEEK_API_KEY)

    @property
    def model_path(self) -> str:
        return f"deepseek://{DEEPSEEK_MODEL}"

    @property
    def model_version(self) -> str:
        return f"deepseek-{DEEPSEEK_MODEL}+ocr"

    def recognize(
        self,
        session_id: str,
        video_uri: str | None,
        device_id: str | None = None,
        recognition_mode: str | None = None,
    ) -> RecognitionOutput:
        frames = self._load_frames(video_uri)
        if not frames:
            return self._empty("no frames for deepseek")
        if not self.available:
            return RecognitionOutput(
                items=[],
                overall_confidence=0.0,
                model_version="deepseek-unavailable",
                need_review=True,
                detected_classes=[self.load_error or "deepseek not configured"],
            )
        return self._recognize_frames(session_id, frames, device_id)

    def recognize_upload(
        self,
        session_id: str,
        data: bytes,
        filename: str,
        device_id: str | None = None,
    ) -> RecognitionOutput:
        if not self.available:
            return RecognitionOutput(
                items=[],
                overall_confidence=0.0,
                model_version="deepseek-unavailable",
                need_review=True,
                detected_classes=[self.load_error or "deepseek not configured"],
            )
        prompt = (
            "你是智能开门柜商品识别助手。根据包装上的 OCR 文字，从 SKU 列表选出最匹配商品。"
            "返回 JSON 数组 [{\"sku_id\",\"quantity\",\"confidence\"}]，无法判断则 []。"
        )
        return self._recognize_frames(
            session_id, [data], device_id, prompt_override=prompt, force_review=True
        )

    def suggest_class_from_image(self, data: bytes, sku_name: str | None = None) -> dict[str, Any]:
        """商品录入：OCR + DeepSeek 建议 yolo_class_name。"""
        ocr = _ocr_text(data)
        prompt = (
            "你是智能开门柜商品录入助手。根据商品包装 OCR 文字生成 snake_case 英文类名，"
            '仅返回 JSON：{"yolo_class_name":"...","confidence":0.0-1.0}。'
        )
        if sku_name:
            prompt += f" 商品名：{sku_name}。"
        if ocr:
            prompt += f" OCR：{ocr[:500]}"
        out = self._call_chat(prompt)
        parsed = _parse_json_blob(out.get("raw_text", ""))
        if parsed and parsed.get("yolo_class_name"):
            return parsed
        slug = _slugify(sku_name or ocr or "sku_unknown")
        return {"yolo_class_name": slug, "confidence": 0.5, "ocr": ocr, "raw_text": out.get("raw_text")}

    def suggest_dispute_skus(
        self, data: bytes, device_id: str | None
    ) -> RecognitionOutput:
        prompt = (
            "智能开门柜争议复核。根据 OCR 文字从 SKU 列表推荐被取走的商品。"
            "返回 JSON 数组 [{\"sku_id\",\"quantity\",\"confidence\"}]，无则 []。"
        )
        return self._recognize_frames("dispute", [data], device_id, prompt_override=prompt, force_review=True)

    def _recognize_frames(
        self,
        session_id: str,
        frames: list[bytes],
        device_id: str | None,
        prompt_override: str | None = None,
        force_review: bool = False,
    ) -> RecognitionOutput:
        ctx = self._sku_context(device_id)
        prompt = prompt_override or (
            "你是 AI 开门柜视觉结算助手。根据开门前后图像 OCR 文字差异，判断取走了哪些商品。"
            "只能从 SKU 列表选择，返回 JSON 数组 "
            '[{"sku_id":"...","quantity":1,"confidence":0.0-1.0}]，无变化则 []。'
        )
        try:
            body: dict[str, Any]
            if DEEPSEEK_FORCE_VISION:
                try:
                    body = self._call_vlm(frames[:2], prompt, ctx)
                except VisionUnsupportedError as exc:
                    log.warning("deepseek vision unsupported, fallback OCR: %s", exc)
                    body = self._call_ocr_then_chat(frames, prompt, ctx)
            else:
                body = self._call_ocr_then_chat(frames, prompt, ctx)

            items = _items_from_parsed(body.get("parsed"), ctx)
            # OCR 直接命中：SKU 名出现在文字里时兜底匹配
            if not items:
                items = _match_skus_from_ocr(body.get("ocr_text", ""), ctx)

            overall = max((i.confidence for i in items), default=0.0)
            need_review = force_review or not DEEPSEEK_AUTO_CHARGE or overall < REVIEW_CONF_THRESHOLD
            if not items:
                need_review = True
            return RecognitionOutput(
                items=items,
                overall_confidence=round(overall, 3),
                model_version=self.model_version,
                need_review=need_review,
                detected_classes=[
                    f"deepseek:{DEEPSEEK_MODEL}",
                    f"ocr:{(body.get('ocr_text') or '')[:80]}",
                ],
            )
        except Exception as exc:
            log.warning("deepseek recognize failed session=%s: %s", session_id, exc)
            return RecognitionOutput(
                items=[],
                overall_confidence=0.0,
                model_version="deepseek-error",
                need_review=True,
                detected_classes=[str(exc)],
            )

    @staticmethod
    def _sku_context(device_id: str | None) -> list[dict[str, Any]]:
        """识别 Demo / OCR：优先全量 catalog；有柜机白名单时合并（防止柜外商品识别不到）。"""
        catalog = [
            {"skuId": sku_id, "skuName": sku_name, "yoloClassName": class_name}
            for sku_id, class_name, sku_name in fetch_catalog_classes()
        ]
        if not device_id:
            return catalog
        device = fetch_device_vision_context(device_id)
        if not device:
            return catalog
        seen = {row.get("skuId") for row in device}
        merged = list(device)
        for row in catalog:
            if row.get("skuId") not in seen:
                merged.append(row)
        return merged

    def _call_ocr_then_chat(
        self, frames: list[bytes], prompt: str, sku_context: list[dict[str, Any]]
    ) -> dict[str, Any]:
        ocr_parts = [_ocr_text(f) for f in frames[:2]]
        ocr_text = " | ".join(p for p in ocr_parts if p).strip()
        text = prompt
        if sku_context:
            text += "\nSKU_LIST:\n" + json.dumps(sku_context, ensure_ascii=False)
        if ocr_text:
            text += f"\nOCR_TEXT:\n{ocr_text[:1500]}"
        else:
            text += "\nOCR_TEXT: (empty — 无法从图片读出文字，请返回 [])"
        out = self._call_chat(text)
        out["ocr_text"] = ocr_text
        return out

    def _call_chat(self, text: str) -> dict[str, Any]:
        payload = {
            "model": DEEPSEEK_MODEL,
            "messages": [{"role": "user", "content": text}],
            "stream": False,
        }
        headers = {
            "Authorization": f"Bearer {DEEPSEEK_API_KEY}",
            "Content-Type": "application/json",
        }
        timeout = DEEPSEEK_TIMEOUT_MS / 1000.0
        with httpx.Client(timeout=timeout) as client:
            resp = client.post(
                f"{DEEPSEEK_BASE_URL}/chat/completions",
                headers=headers,
                json=payload,
            )
            resp.raise_for_status()
            data = resp.json()
        raw_text = (
            data.get("choices", [{}])[0]
            .get("message", {})
            .get("content", "")
        )
        return {"raw_text": raw_text, "parsed": _parse_json_blob(raw_text)}

    def _call_vlm(
        self, images: list[bytes], prompt: str, sku_context: list[dict[str, Any]]
    ) -> dict[str, Any]:
        content: list[dict[str, Any]] = []
        for img in images:
            b64 = base64.b64encode(img).decode("ascii")
            content.append(
                {
                    "type": "image_url",
                    "image_url": {"url": f"data:image/jpeg;base64,{b64}"},
                }
            )
        text = prompt
        if sku_context:
            text += "\nSKU_LIST:\n" + json.dumps(sku_context, ensure_ascii=False)
        content.append({"type": "text", "text": text})
        payload = {
            "model": DEEPSEEK_MODEL,
            "messages": [{"role": "user", "content": content}],
            "stream": False,
        }
        headers = {
            "Authorization": f"Bearer {DEEPSEEK_API_KEY}",
            "Content-Type": "application/json",
        }
        timeout = DEEPSEEK_TIMEOUT_MS / 1000.0
        with httpx.Client(timeout=timeout) as client:
            resp = client.post(
                f"{DEEPSEEK_BASE_URL}/chat/completions",
                headers=headers,
                json=payload,
            )
            if resp.status_code == 400 and "image_url" in resp.text:
                raise VisionUnsupportedError(resp.text[:300])
            resp.raise_for_status()
            data = resp.json()
        raw_text = (
            data.get("choices", [{}])[0]
            .get("message", {})
            .get("content", "")
        )
        return {"raw_text": raw_text, "parsed": _parse_json_blob(raw_text)}

    @staticmethod
    def _load_frames(video_uri: str | None) -> list[bytes]:
        if not video_uri:
            return []
        local = resolve_video_path(video_uri)
        if not local or not os.path.exists(local):
            return []
        paths = extract_key_frames(local, max_frames=2)
        out: list[bytes] = []
        for p in paths:
            try:
                out.append(open(p, "rb").read())
            except OSError:
                continue
        return out

    def _empty(self, reason: str) -> RecognitionOutput:
        return RecognitionOutput(
            items=[],
            overall_confidence=0.0,
            model_version="deepseek-empty",
            need_review=True,
            detected_classes=[reason],
        )


class VisionUnsupportedError(RuntimeError):
    pass


def _ocr_text(data: bytes) -> str:
    """优先 RapidOCR；不可用时退回空串。"""
    try:
        from rapidocr_onnxruntime import RapidOCR  # type: ignore

        engine = RapidOCR()
        result, _ = engine(data)
        if not result:
            return ""
        lines = [str(row[1]) for row in result if len(row) >= 2 and row[1]]
        return " ".join(lines)
    except Exception as exc:
        log.warning("OCR unavailable: %s", exc)
        return ""


def _match_skus_from_ocr(ocr_text: str, ctx: list[dict[str, Any]]) -> list[RecognizedItem]:
    if not ocr_text or not ctx:
        return []
    # OCR 常见谐音/错字：莱莉≈茉莉
    normalized = (
        ocr_text.replace(" ", "")
        .replace("莱莉", "茉莉")
    )
    hits: list[RecognizedItem] = []
    for row in ctx:
        name = str(row.get("skuName") or "")
        sku_id = str(row.get("skuId") or "")
        if not sku_id or not name:
            continue
        name_norm = name.replace(" ", "")
        if name_norm and name_norm in normalized:
            hits.append(RecognizedItem(sku_id=sku_id, quantity=1, confidence=0.9))
            continue
        # 去掉规格后的品名核心（如「东方树叶茉莉花茶」）
        core = re.sub(r"[\d\.]+(ml|g|L|毫升|克)?", "", name_norm, flags=re.I).strip()
        if len(core) >= 4 and core in normalized:
            hits.append(RecognizedItem(sku_id=sku_id, quantity=1, confidence=0.86))
            continue
        # 品牌短词（前 4 个汉字）如「东方树叶」
        brand = re.sub(r"[^\u4e00-\u9fff]", "", name_norm)[:4]
        if len(brand) >= 4 and brand in normalized:
            hits.append(RecognizedItem(sku_id=sku_id, quantity=1, confidence=0.8))
    return hits


def _parse_json_blob(text: str) -> Any:
    if not text:
        return None
    text = text.strip()
    # 去掉 deepseek thinking 包裹
    text = re.sub(r"<think>[\s\S]*?</think>", "", text).strip()
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass
    match = re.search(r"\[[\s\S]*\]", text)
    if match:
        try:
            return json.loads(match.group(0))
        except json.JSONDecodeError:
            pass
    match = re.search(r"\{[\s\S]*\}", text)
    if match:
        try:
            return json.loads(match.group(0))
        except json.JSONDecodeError:
            pass
    return None


def _items_from_parsed(parsed: Any, ctx: list[dict[str, Any]]) -> list[RecognizedItem]:
    allowed = {row.get("skuId") or row.get("sku_id") for row in ctx}
    rows = parsed if isinstance(parsed, list) else []
    if isinstance(parsed, dict) and "items" in parsed:
        rows = parsed["items"]
    items: list[RecognizedItem] = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        sku_id = str(row.get("sku_id") or row.get("skuId") or "")
        if not sku_id:
            continue
        if allowed and sku_id not in allowed:
            continue
        qty = int(row.get("quantity") or row.get("qty") or 1)
        conf = float(row.get("confidence") or row.get("conf") or 0.0)
        items.append(RecognizedItem(sku_id=sku_id, quantity=max(1, qty), confidence=min(0.99, conf)))
    return items


def _slugify(name: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")
    return slug[:48] if slug else "sku_unknown"
