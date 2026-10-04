#!/usr/bin/env python3
"""将 raw/ 下已标注样本重新划分 train/val/test。"""

from __future__ import annotations

import argparse
import random
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATASET = ROOT / "datasets" / "cabinet-skus-v1"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=DATASET / "raw" / "labeled")
    parser.add_argument("--train", type=float, default=0.70)
    parser.add_argument("--val", type=float, default=0.15)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    if not args.source.exists():
        print(f"source not found: {args.source}")
        return 1

    pairs: list[tuple[Path, Path | None]] = []
    img_dir = args.source / "images"
    lbl_dir = args.source / "labels"
    for img in sorted(img_dir.glob("*")):
        if img.suffix.lower() not in {".jpg", ".jpeg", ".png"}:
            continue
        lbl = lbl_dir / f"{img.stem}.txt"
        pairs.append((img, lbl if lbl.exists() else None))

    rng = random.Random(args.seed)
    rng.shuffle(pairs)
    n = len(pairs)
    n_train = int(n * args.train)
    n_val = int(n * args.val)
    splits = {
        "train": pairs[:n_train],
        "val": pairs[n_train : n_train + n_val],
        "test": pairs[n_train + n_val :],
    }

    for split, items in splits.items():
        for kind in ("images", "labels"):
            d = DATASET / kind / split
            d.mkdir(parents=True, exist_ok=True)
        for img, lbl in items:
            dest_img = DATASET / "images" / split / img.name
            shutil.copy2(img, dest_img)
            if lbl:
                shutil.copy2(lbl, DATASET / "labels" / split / lbl.name)
            else:
                (DATASET / "labels" / split / f"{img.stem}.txt").write_text("", encoding="utf-8")

    print(f"split {n} images -> train={len(splits['train'])} val={len(splits['val'])} test={len(splits['test'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
