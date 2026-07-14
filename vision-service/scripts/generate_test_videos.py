#!/usr/bin/env python3
"""生成视觉识别回归用短视频（开门前后差异测试）。"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TESTDATA = ROOT / "testdata"
BOTTLE = TESTDATA / "bottle.jpg"
COLA = TESTDATA / "cola.png"


def _load_image(path: Path):
    import cv2  # type: ignore
    import numpy as np  # type: ignore

    img = cv2.imread(str(path))
    if img is None:
        try:
            from PIL import Image
            pil = Image.open(path).convert("RGB")
            img = cv2.cvtColor(np.array(pil), cv2.COLOR_RGB2BGR)
        except Exception as exc:
            raise FileNotFoundError(f"cannot read image: {path} ({exc})") from exc
    return cv2.resize(img, (640, 480))


def _blank_frame():
    import numpy as np  # type: ignore

    return np.zeros((480, 640, 3), dtype="uint8")


def write_video(out: Path, frames: list) -> None:
    import cv2  # type: ignore

    out.parent.mkdir(parents=True, exist_ok=True)
    writer = cv2.VideoWriter(
        str(out),
        cv2.VideoWriter_fourcc(*"mp4v"),
        5.0,
        (640, 480),
    )
    for frame in frames:
        writer.write(frame)
    writer.release()
    print(f"wrote {out} ({len(frames)} frames)")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out-dir", type=Path, default=TESTDATA)
    args = parser.parse_args()

    try:
        import cv2  # noqa: F401
        import numpy  # noqa: F401
    except ImportError:
        print("install: pip install opencv-python-headless numpy", file=sys.stderr)
        return 1

    source = COLA if COLA.exists() else BOTTLE
    if not source.exists():
        print(f"missing source image under {TESTDATA}", file=sys.stderr)
        return 1

    bottle = _load_image(source)
    blank = _blank_frame()

    out_dir = args.out_dir
    # 开门 2 瓶 → 关门 1 瓶（delta 应识别 taken=1）
    write_video(out_dir / "take-one-bottle.mp4", [bottle, bottle, bottle, bottle, blank])
    # 全程有瓶（delta 应无 taken，进审核）
    write_video(out_dir / "static-bottle.mp4", [bottle] * 6)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
