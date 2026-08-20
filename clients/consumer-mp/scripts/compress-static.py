from pathlib import Path
from PIL import Image
import io

root = Path(__file__).resolve().parents[1] / "src" / "static"
targets = list(root.rglob("*.jpg")) + list(root.rglob("*.jpeg")) + list(root.rglob("*.png"))
changed = []
for p in targets:
    size = p.stat().st_size
    if size < 80 * 1024:
        continue
    img = Image.open(p)
    if img.mode in ("RGBA", "P"):
        img = img.convert("RGB")
    max_side = 1280 if "bg-" in p.name else 800
    w, h = img.size
    scale = min(1.0, max_side / max(w, h))
    if scale < 1.0:
        img = img.resize((int(w * scale), int(h * scale)), Image.Resampling.LANCZOS)
    quality = 78
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=quality, optimize=True, progressive=True)
    data = buf.getvalue()
    while len(data) > 180 * 1024 and quality > 55:
        quality -= 8
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=quality, optimize=True, progressive=True)
        data = buf.getvalue()
    if len(data) < size * 0.95:
        out = p if p.suffix.lower() in (".jpg", ".jpeg") else p.with_suffix(".jpg")
        out.write_bytes(data)
        if out != p:
            p.unlink()
        changed.append((str(p.relative_to(root)), size, len(data), quality))
print("compressed", len(changed))
for rel, before, after, q in changed:
    print(f"{rel}: {before/1024:.1f}KB -> {after/1024:.1f}KB q={q}")
