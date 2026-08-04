#!/usr/bin/env python3
"""Fine-tune YOLOv8 on cabinet SKU dataset.

Prerequisites:
  pip install -r requirements-ml.txt
  Labeled dataset under vision-service/datasets/ (YOLO bbox format)

Usage:
  cd vision-service
  python training/train_sku_yolo.py --epochs 80 --imgsz 640
  python training/train_sku_yolo.py --export-only runs/detect/cabinet-skus-v1/weights/best.pt
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TRAINING_DIR = ROOT / "training"
DATA_YAML = TRAINING_DIR / "data.yaml"
MODELS_DIR = ROOT / "models"
RUNS_DIR = ROOT / "runs" / "detect"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def export_model(src: Path, version: str, classes_json: Path | None = None) -> Path:
    if not src.exists():
        raise FileNotFoundError(f"weights not found: {src}")
    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    dest = MODELS_DIR / f"{version}.pt"
    shutil.copy2(src, dest)
    classes_path = classes_json or (TRAINING_DIR / "classes.json")
    manifest = {
        "version": version,
        "source": str(src),
        "exported_at": datetime.now(timezone.utc).isoformat(),
        "sha256": sha256_file(dest),
        "classes": (json.loads(classes_path.read_text(encoding="utf-8"))
                    if classes_path.exists() else None),
    }
    (MODELS_DIR / f"{version}.manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"exported {dest} ({dest.stat().st_size} bytes)")
    print(f"manifest {MODELS_DIR / f'{version}.manifest.json'}")
    return dest


def train(args: argparse.Namespace) -> Path:
    from ultralytics import YOLO  # type: ignore

    data_yaml = Path(args.data_yaml) if args.data_yaml else DATA_YAML
    if not data_yaml.exists():
        raise FileNotFoundError(f"missing dataset config: {data_yaml}")

    base = args.base_model
    model = YOLO(base)
    run_name = args.run_name
    results = model.train(
        data=str(data_yaml),
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=args.batch,
        project=str(RUNS_DIR),
        name=run_name,
        exist_ok=True,
        pretrained=True,
    )
    best = Path(results.save_dir) / "weights" / "best.pt"
    classes_json = Path(args.classes_json) if args.classes_json else None
    return export_model(best, args.version, classes_json)


def main() -> None:
    parser = argparse.ArgumentParser(description="Train/export cabinet SKU YOLO model")
    parser.add_argument("--base-model", default="yolov8n.pt", help="Ultralytics base checkpoint")
    parser.add_argument("--epochs", type=int, default=80)
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--batch", type=int, default=16)
    parser.add_argument("--run-name", default="cabinet-skus-v1")
    parser.add_argument("--version", default="cabinet-skus-v1.0.0", help="Exported filename stem")
    parser.add_argument("--data-yaml", help="Dataset yaml (default training/data.yaml)")
    parser.add_argument("--classes-json", help="Classes manifest for export (default training/classes.json)")
    parser.add_argument("--export-only", help="Path to existing best.pt to export into models/")
    args = parser.parse_args()

    if args.export_only:
        classes_json = Path(args.classes_json) if args.classes_json else None
        export_model(Path(args.export_only), args.version, classes_json)
        return

    train(args)


if __name__ == "__main__":
    main()
