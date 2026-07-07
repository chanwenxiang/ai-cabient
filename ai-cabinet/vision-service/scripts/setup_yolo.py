"""下载 YOLOv8n 模型到 models/yolov8n.pt"""

from __future__ import annotations

import os
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODEL_DIR = ROOT / "models"
MODEL_PATH = MODEL_DIR / "yolov8n.pt"


def main() -> int:
    try:
        from ultralytics import YOLO
    except ImportError:
        print("请先安装依赖: pip install ultralytics opencv-python-headless")
        return 1

    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    if MODEL_PATH.exists() and MODEL_PATH.stat().st_size > 0:
        print(f"模型已存在: {MODEL_PATH}")
        return 0

    print("正在下载 yolov8n.pt（首次约 1 分钟）...")
    model = YOLO("yolov8n.pt")
    src = getattr(model, "ckpt_path", None) or ROOT / "yolov8n.pt"
    src_path = Path(str(src))
    if src_path.exists():
        shutil.copy2(src_path, MODEL_PATH)
    elif (ROOT / "yolov8n.pt").exists():
        shutil.copy2(ROOT / "yolov8n.pt", MODEL_PATH)

    if MODEL_PATH.exists():
        print(f"完成: {MODEL_PATH} ({MODEL_PATH.stat().st_size // 1024} KB)")
        return 0

    print("下载失败，请检查网络或手动将 yolov8n.pt 放到 models/ 目录")
    return 1


if __name__ == "__main__":
  sys.exit(main())
