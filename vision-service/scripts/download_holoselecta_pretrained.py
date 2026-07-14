#!/usr/bin/env python3
"""下载/准备 HoloSelecta 或 RPC 零售预训练权重（迁移学习起点）。

用法:
  python scripts/download_holoselecta_pretrained.py
  python scripts/download_holoselecta_pretrained.py --url https://example.com/retail-pretrain.pt
"""

from __future__ import annotations

import argparse
import shutil
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODELS = ROOT / "models"
DEFAULT_OUT = MODELS / "holoselecta-pretrain.pt"

# 公开 YOLOv8 零售迁移起点（无 HoloSelecta 权重时可先用 COCO 预训练）
FALLBACK_URL = "https://github.com/ultralytics/assets/releases/download/v8.3.0/yolov8n.pt"


def download(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    print(f"downloading {url} -> {dest}")
    urllib.request.urlretrieve(url, dest)
    print(f"saved {dest.stat().st_size} bytes")


def main() -> int:
    parser = argparse.ArgumentParser(description="Download retail YOLO pretrain weights")
    parser.add_argument("--url", default=FALLBACK_URL, help="Pretrain .pt URL")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--link-as", type=str, default="", help="Also symlink to models/{name}")
    args = parser.parse_args()

    if args.out.exists() and args.out.stat().st_size > 0:
        print(f"exists: {args.out}")
    else:
        try:
            download(args.url, args.out)
        except Exception as exc:
            print(f"download failed: {exc}", file=sys.stderr)
            return 1

    if args.link_as:
        link = MODELS / args.link_as
        if link.exists():
            link.unlink()
        shutil.copy2(args.out, link)
        print(f"copied to {link}")

    print("next: python training/train_sku_yolo.py --weights", args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
