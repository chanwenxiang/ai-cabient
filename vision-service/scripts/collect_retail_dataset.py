#!/usr/bin/env python3
"""搜集 cabinet-retail-v1 开放词表训练集：网络图 + 本地 SKU 图 + 合成柜内场景。

目标：覆盖市面常见零售商品大类（~40+ 类），供 YOLO 检测 + 运营后台映射到 sku_catalog。

用法:
  cd vision-service
  python scripts/collect_retail_dataset.py
  python scripts/collect_retail_dataset.py --per-class 60 --no-web
"""

from __future__ import annotations

import argparse
import json
import random
import shutil
import ssl
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO = ROOT.parent
TAXONOMY = ROOT / "datasets" / "retail_taxonomy.json"
DATASET = ROOT / "datasets" / "cabinet-retail-v1"
TRAINING = ROOT / "training"
WEB_CACHE = DATASET / "raw" / "web"
META = DATASET / "meta"

USER_AGENT = "AiCabinetDatasetBot/1.0 (retail vision training; contact: dev@local)"


@dataclass
class RetailClass:
    idx: int
    class_name: str
    name_zh: str
    category: str
    image_urls: list[str]
    local_paths: list[Path]


@dataclass
class Box:
    cls: int
    cx: float
    cy: float
    w: float
    h: float

    def yolo_line(self) -> str:
        return f"{self.cls} {self.cx:.6f} {self.cy:.6f} {self.w:.6f} {self.h:.6f}"


def _import_cv():
    try:
        import cv2  # type: ignore
        import numpy as np  # type: ignore
        return cv2, np
    except ImportError as exc:
        print("pip install opencv-python-headless numpy pillow", file=sys.stderr)
        raise SystemExit(1) from exc


def load_taxonomy() -> list[RetailClass]:
    data = json.loads(TAXONOMY.read_text(encoding="utf-8"))
    classes: list[RetailClass] = []
    idx = 0
    for cat in data.get("categories", []):
        cat_id = cat.get("id", "misc")
        for item in cat.get("classes", []):
            locals_ = [REPO / p for p in item.get("local_paths", [])]
            classes.append(
                RetailClass(
                    idx=idx,
                    class_name=item["class_name"],
                    name_zh=item.get("name_zh", item["class_name"]),
                    category=cat_id,
                    image_urls=list(item.get("image_urls", [])),
                    local_paths=locals_,
                )
            )
            idx += 1
    return classes


def download_url(url: str, dest: Path, timeout: int = 25) -> bool:
    if dest.exists() and dest.stat().st_size > 1024:
        return True
    dest.parent.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    ctx = ssl.create_default_context()
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
            data = resp.read()
        if len(data) < 512:
            return False
        dest.write_bytes(data)
        return True
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        print(f"  warn download failed {url[:80]}... : {exc}", file=sys.stderr)
        return False


def load_rgba(path: Path, cv2, np):
    from PIL import Image

    if not path.exists():
        return None
    if path.suffix.lower() in {".png", ".webp", ".svg"}:
        try:
            pil = Image.open(path).convert("RGBA")
            arr = np.array(pil)
            if arr.shape[2] == 4:
                return cv2.cvtColor(arr, cv2.COLOR_RGBA2BGRA)
            return cv2.cvtColor(arr, cv2.COLOR_RGB2BGR)
        except Exception:
            return None
    img = cv2.imread(str(path), cv2.IMREAD_UNCHANGED)
    if img is None:
        try:
            pil = Image.open(path).convert("RGB")
            img = cv2.cvtColor(np.array(pil), cv2.COLOR_RGB2BGR)
        except Exception:
            return None
    return img


def procedural_product(class_name: str, category: str, cv2, np, w: int = 180, h: int = 240):
    """无素材时生成简易商品剪影（罐/瓶/袋）。"""
    palette = {
        "beverage_carbonated": (40, 80, 200),
        "beverage_water_tea": (60, 140, 200),
        "dairy": (220, 220, 240),
        "snacks_chips": (40, 160, 60),
        "snacks_sweet": (50, 100, 220),
        "instant_food": (30, 120, 180),
        "legacy_demo": (180, 80, 40),
    }
    base = palette.get(category, (100, 100, 120))
    rng = random.Random(hash(class_name) & 0xFFFF)
    color = tuple(int(c * rng.uniform(0.7, 1.1)) for c in base)
    color = tuple(min(255, max(0, c)) for c in color)

    img = np.zeros((h, w, 4), dtype=np.uint8)
    if "cup" in class_name or "noodle" in class_name or "bowl" in class_name:
        cv2.rectangle(img, (20, 40), (w - 20, h - 10), (*color, 255), -1)
        cv2.ellipse(img, (w // 2, 40), (w // 2 - 25, 18), 0, 0, 360, (*color, 255), -1)
    elif "bag" in class_name or "chips" in class_name or "pack" in class_name:
        pts = np.array([[w // 2, 10], [w - 15, h - 15], [15, h - 15]], np.int32)
        cv2.fillPoly(img, [pts], (*color, 255))
    else:
        cv2.rectangle(img, (35, 15), (w - 35, h - 15), (*color, 255), -1)
        cv2.rectangle(img, (45, 5), (w - 45, 25), (200, 200, 200, 255), -1)
    return img


def shelf_background(w: int, h: int, cv2, np, variant: int = 0):
    base = np.full((h, w, 3), 28 + variant * 3, dtype=np.uint8)
    for y in range(h):
        shade = int(35 + (y / max(h - 1, 1)) * 45 + variant * 2)
        base[y, :, :] = (shade, shade + 5, shade + 8)
    for row in range(2, 5):
        y = int(h * row / 5)
        cv2.rectangle(base, (0, y - 2), (w, y + 2), (55, 58, 62), -1)
    return base


def paste_product(bg, product_bgra, cv2, np, rng: random.Random) -> Box | None:
    if product_bgra is None:
        return None
    h, w = bg.shape[:2]
    prod = product_bgra if product_bgra.shape[2] == 4 else cv2.cvtColor(product_bgra, cv2.COLOR_BGR2BGRA)
    scale = rng.uniform(0.22, 0.55)
    target_w = max(int(w * scale), 40)
    aspect = prod.shape[0] / max(prod.shape[1], 1)
    target_h = max(int(target_w * aspect), 40)
    prod = cv2.resize(prod, (target_w, target_h), interpolation=cv2.INTER_AREA)
    if rng.random() < 0.5:
        prod = cv2.flip(prod, 1)
    x1 = rng.randint(20, max(21, w - target_w - 20))
    y1 = rng.randint(int(h * 0.15), max(int(h * 0.15) + 1, h - target_h - 30))
    actual_h = min(target_h, h - y1)
    actual_w = min(target_w, w - x1)
    prod = prod[:actual_h, :actual_w]
    if prod.shape[2] == 4:
        alpha = prod[:, :, 3:4].astype(float) / 255.0
        rgb = prod[:, :, :3].astype(float)
    else:
        alpha = np.ones((actual_h, actual_w, 1), dtype=float)
        rgb = prod[:, :, :3].astype(float)
    roi = bg[y1 : y1 + actual_h, x1 : x1 + actual_w].astype(float)
    bg[y1 : y1 + actual_h, x1 : x1 + actual_w] = (roi * (1 - alpha) + rgb * alpha).astype(np.uint8)
    return Box(cls=-1, cx=(x1 + actual_w / 2) / w, cy=(y1 + actual_h / 2) / h, w=actual_w / w, h=actual_h / h)


def augment(img, cv2, np, rng: random.Random):
    out = img.copy()
    if rng.random() < 0.6:
        out = np.clip(out.astype(float) * rng.uniform(0.75, 1.25), 0, 255).astype(np.uint8)
    if rng.random() < 0.3:
        out = cv2.GaussianBlur(out, (rng.choice([3, 5]),) * 2, 0)
    if rng.random() < 0.25:
        noise = np.random.default_rng(rng.randint(0, 2**31)).integers(-12, 13, out.shape, dtype=np.int16)
        out = np.clip(out.astype(np.int16) + noise, 0, 255).astype(np.uint8)
    return out


def write_sample(split: str, stem: str, img, boxes: list[Box], cv2) -> None:
    img_dir = DATASET / "images" / split
    lbl_dir = DATASET / "labels" / split
    img_dir.mkdir(parents=True, exist_ok=True)
    lbl_dir.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(img_dir / f"{stem}.jpg"), img, [int(cv2.IMWRITE_JPEG_QUALITY), random.randint(82, 95)])
    lines = [b.yolo_line() for b in boxes if b.cls >= 0]
    (lbl_dir / f"{stem}.txt").write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")


def collect_web_sources(classes: list[RetailClass], use_web: bool) -> dict[str, list[Path]]:
    sources: dict[str, list[Path]] = {c.class_name: [] for c in classes}
    if not use_web:
        return sources
    print("=== download web images ===")
    for rc in classes:
        for i, url in enumerate(rc.image_urls):
            ext = ".jpg"
            if url.lower().endswith(".png"):
                ext = ".png"
            elif url.lower().endswith(".svg"):
                ext = ".svg"
            dest = WEB_CACHE / rc.class_name / f"web_{i:02d}{ext}"
            if download_url(url, dest):
                sources[rc.class_name].append(dest)
    return sources


def build_class_sources(classes: list[RetailClass], web_sources: dict[str, list[Path]]) -> dict[str, list[Path]]:
    out: dict[str, list[Path]] = {}
    for rc in classes:
        paths = [p for p in rc.local_paths if p.exists()]
        paths.extend(web_sources.get(rc.class_name, []))
        out[rc.class_name] = paths
    return out


def bootstrap_synthetic(classes: list[RetailClass], sources: dict[str, list[Path]], per_class: int, cv2, np, rng: random.Random):
    counts: dict[str, int] = {}
    splits = {"train": 0.70, "val": 0.15, "test": 0.15}
    w, h = 640, 480

    for rc in classes:
        cls_sources = sources.get(rc.class_name, [])
        counts[rc.class_name] = 0
        for i in range(per_class):
            split = "train" if i < int(per_class * splits["train"]) else (
                "val" if i < int(per_class * (splits["train"] + splits["val"])) else "test"
            )
            bg = shelf_background(w, h, cv2, np, variant=i % 9)
            n_items = 1 if rng.random() < 0.8 else rng.randint(2, 3)
            boxes: list[Box] = []
            for _ in range(n_items):
                if cls_sources:
                    src = rng.choice(cls_sources)
                    prod = load_rgba(src, cv2, np)
                else:
                    prod = procedural_product(rc.class_name, rc.category, cv2, np)
                box = paste_product(bg, prod, cv2, np, rng)
                if box:
                    box.cls = rc.idx
                    boxes.append(box)
            if rng.random() < 0.08:
                boxes = []
            bg = augment(bg, cv2, np, rng)
            write_sample(split, f"retail_{rc.class_name}_{i:04d}", bg, boxes, cv2)
            counts[rc.class_name] += 1
    return counts


def write_data_yaml(classes: list[RetailClass]) -> None:
    names = [c.class_name for c in classes]
    yaml_text = (
        "# Auto-generated by collect_retail_dataset.py\n"
        f"path: {DATASET.as_posix()}\n"
        "train: images/train\n"
        "val: images/val\n"
        "test: images/test\n"
        f"nc: {len(names)}\n"
        "names:\n"
    )
    for n in names:
        yaml_text += f"  - {n}\n"
    (TRAINING / "data-retail.yaml").write_text(yaml_text, encoding="utf-8")
    (TRAINING / "classes-retail.json").write_text(
        json.dumps(
            [{"id": c.idx, "class_name": c.class_name, "name_zh": c.name_zh, "category": c.category} for c in classes],
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )


def write_meta(classes: list[RetailClass], counts: dict[str, int], sources: dict[str, list[Path]]) -> None:
    META.mkdir(parents=True, exist_ok=True)
    (META / "labeling-guide.md").write_text(
        "# cabinet-retail-v1 开放词表数据集\n\n"
        "Tier-1 检测类（本模型）→ 运营后台 `sku_vision_mapping` 映射到商户 SKU。\n\n"
        "素材来源：Wikimedia 等公开图 + 演示 SKU 静态图 + 程序合成柜内场景。\n\n"
        "**生产注意**：合成/网络图仅用于开训与 demo；真实扣款前需柜内实拍增量标注 + §10 门禁。\n",
        encoding="utf-8",
    )
    (META / "collection-stats.json").write_text(
        json.dumps(
            {
                "num_classes": len(classes),
                "per_class_counts": counts,
                "sources_per_class": {k: len(v) for k, v in sources.items()},
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Collect retail open-vocabulary YOLO dataset")
    parser.add_argument("--per-class", type=int, default=55)
    parser.add_argument("--no-web", action="store_true", help="Skip web download")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    if not TAXONOMY.exists():
        print(f"missing taxonomy: {TAXONOMY}", file=sys.stderr)
        return 1

    cv2, np = _import_cv()
    rng = random.Random(args.seed)
    classes = load_taxonomy()
    print(f"taxonomy: {len(classes)} classes")

    for sub in ("train", "val", "test"):
        for kind in ("images", "labels"):
            d = DATASET / kind / sub
            if d.exists():
                shutil.rmtree(d)

    web_sources = collect_web_sources(classes, use_web=not args.no_web)
    sources = build_class_sources(classes, web_sources)
    with_src = sum(1 for c in classes if sources[c.class_name])
    print(f"classes with real/web sources: {with_src}/{len(classes)}")

    print(f"=== bootstrap synthetic ({args.per_class}/class) ===")
    counts = bootstrap_synthetic(classes, sources, args.per_class, cv2, np, rng)
    write_data_yaml(classes)
    write_meta(classes, counts, sources)

    total = sum(len(list((DATASET / "images" / s).glob("*.jpg"))) for s in ("train", "val", "test"))
    print(f"=== done: {total} images, nc={len(classes)} ===")
    print(f"data yaml: {TRAINING / 'data-retail.yaml'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
