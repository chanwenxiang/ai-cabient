#!/usr/bin/env python3
"""校验 YOLO 标注与图片配对。"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATASET = ROOT / "datasets" / "cabinet-skus-v1"
DEFAULT_NUM_CLASSES = 6


def num_classes_from_yaml(path: Path) -> int | None:
    if not path.exists():
        return None
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("nc:"):
            return int(line.split(":", 1)[1].strip())
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, default=DATASET)
    parser.add_argument("--data-yaml", type=Path, default=None, help="Read nc from yaml")
    parser.add_argument("--num-classes", type=int, default=None)
    args = parser.parse_args()
    num_classes = args.num_classes
    if num_classes is None and args.data_yaml:
        num_classes = num_classes_from_yaml(args.data_yaml)
    if num_classes is None:
        num_classes = DEFAULT_NUM_CLASSES
    errors: list[str] = []
    stats = {"images": 0, "labels": 0, "boxes": 0, "empty": 0}

    for split in ("train", "val", "test"):
        img_dir = args.dataset / "images" / split
        lbl_dir = args.dataset / "labels" / split
        if not img_dir.exists():
            continue
        for img in sorted(img_dir.glob("*")):
            if img.suffix.lower() not in {".jpg", ".jpeg", ".png"}:
                continue
            stats["images"] += 1
            lbl = lbl_dir / f"{img.stem}.txt"
            if not lbl.exists():
                errors.append(f"missing label: {lbl}")
                continue
            stats["labels"] += 1
            text = lbl.read_text(encoding="utf-8").strip()
            if not text:
                stats["empty"] += 1
                continue
            for ln, line in enumerate(text.splitlines(), 1):
                parts = line.split()
                if len(parts) != 5:
                    errors.append(f"bad line {lbl}:{ln}: {line}")
                    continue
                cls = int(float(parts[0]))
                if cls < 0 or cls >= num_classes:
                    errors.append(f"class out of range {lbl}:{ln}: {cls}")
                stats["boxes"] += 1
                for val in parts[1:]:
                    f = float(val)
                    if f < 0 or f > 1:
                        errors.append(f"coord out of range {lbl}:{ln}: {val}")

        # orphan labels
        if lbl_dir.exists():
            for lbl in lbl_dir.glob("*.txt"):
                img_candidates = [
                    img_dir / f"{lbl.stem}.jpg",
                    img_dir / f"{lbl.stem}.jpeg",
                    img_dir / f"{lbl.stem}.png",
                ]
                if not any(p.exists() for p in img_candidates):
                    errors.append(f"orphan label: {lbl}")

    print(f"images={stats['images']} labels={stats['labels']} boxes={stats['boxes']} empty={stats['empty']}")
    if errors:
        print(f"FAILED {len(errors)} issues:", file=sys.stderr)
        for e in errors[:20]:
            print(f"  - {e}", file=sys.stderr)
        return 1
    print("validate_labels OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
