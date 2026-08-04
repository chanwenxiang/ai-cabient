"""一键检查 YOLO 环境 — 在 PyCharm Terminal 运行: python scripts/check_yolo.py"""

from __future__ import annotations

import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))


def main() -> int:
    print("Python:", sys.executable)
    print("vision-service root:", ROOT)

    model = ROOT / "models" / "yolov8n.pt"
    print("model file:", model, "exists=", model.exists())

    try:
        import ultralytics
        print("ultralytics:", ultralytics.__version__)
    except ImportError as e:
        print("FAIL: ultralytics 未安装 -> pip install ultralytics opencv-python-headless")
        print(e)
        return 1

    from app.recognizer import YoloRecognizer

    r = YoloRecognizer()
    print("yolo_loaded:", r.available)
    print("load_error:", r.load_error)
    if r.available:
        print("OK — 请重启 run.py，控制台应显示 yolo_loaded = True")
        return 0
    print("FAIL — 模型未加载，请根据 load_error 排查")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
