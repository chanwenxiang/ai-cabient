#!/usr/bin/env python3
"""将 cabinet-skus YOLO .pt 导出为 RK3588 RKNN（需在 Linux + rknn-toolkit2 环境执行）。

用法:
  python scripts/export_rknn.py --weights models/cabinet-skus-v1.0.0.pt --out models/cabinet-skus-v1.0.0.rknn
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def export_onnx(weights: Path, onnx_out: Path) -> None:
    from ultralytics import YOLO

    model = YOLO(str(weights))
    model.export(format="onnx", imgsz=640, simplify=True, opset=12)
    exported = weights.with_suffix(".onnx")
    if exported.exists():
        exported.replace(onnx_out)
    print(f"onnx: {onnx_out}")


def export_rknn(onnx: Path, rknn_out: Path, quant: bool) -> None:
    try:
        from rknn.api import RKNN
    except ImportError as exc:
        raise SystemExit(
            "rknn-toolkit2 not installed. Run on RK3588 build host or install rknn-toolkit2."
        ) from exc

    rknn = RKNN(verbose=True)
    rknn.config(mean_values=[[0, 0, 0]], std_values=[[255, 255, 255]], target_platform="rk3588")
    rknn.load_onnx(str(onnx))
    rknn.build(do_quantization=quant, dataset=str(ROOT / "datasets/cabinet-skus-v1/images/val"))
    rknn.export_rknn(str(rknn_out))
    rknn.release()
    print(f"rknn: {rknn_out}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Export YOLO pt -> RKNN for edge")
    parser.add_argument("--weights", type=Path, default=ROOT / "models/cabinet-skus-v1.0.0.pt")
    parser.add_argument("--out", type=Path, default=ROOT / "models/cabinet-skus-v1.0.0.rknn")
    parser.add_argument("--skip-rknn", action="store_true", help="Only export ONNX")
    parser.add_argument("--no-quant", action="store_true")
    args = parser.parse_args()

    if not args.weights.exists():
        print(f"weights not found: {args.weights}", file=sys.stderr)
        return 1

    onnx_out = args.out.with_suffix(".onnx")
    export_onnx(args.weights, onnx_out)

    if args.skip_rknn:
        return 0

    try:
        export_rknn(onnx_out, args.out, quant=not args.no_quant)
    except SystemExit as exc:
        print(exc, file=sys.stderr)
        print("ONNX exported; complete RKNN conversion on target hardware.", file=sys.stderr)
        return 0
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
