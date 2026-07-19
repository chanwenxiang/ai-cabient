#!/usr/bin/env python3
"""探测阿里云 ClassifyCommodity 返回的类目 ID，用于填写 aliyun_category_mapping。

用法（在 vision-service 目录，已配置 .env 或环境变量）：
  python scripts/probe_aliyun_category.py path/to/cabinet-photo.jpg
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.recognition.aliyun_recognizer import AliyunGoodsRecognizer  # noqa: E402


def main() -> int:
    if len(sys.argv) < 2:
        print("用法: python scripts/probe_aliyun_category.py <image-path>")
        return 1
    image = Path(sys.argv[1])
    if not image.is_file():
        print(f"文件不存在: {image}")
        return 1
    rec = AliyunGoodsRecognizer()
    if not rec.available:
        print("阿里云客户端未就绪:", rec.load_error)
        return 2
    out = rec.recognize_upload("probe", image.read_bytes(), image.name)
    print(json.dumps({
        "model_version": out.model_version,
        "overall_confidence": out.overall_confidence,
        "need_review": out.need_review,
        "detected_classes": out.detected_classes,
        "items": [{"sku_id": i.sku_id, "qty": i.quantity, "conf": i.confidence} for i in out.items],
    }, ensure_ascii=False, indent=2))
    print("\n将 detected_classes 中的类目 ID 填入运营后台「视觉映射 → 阿里云类目」")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
