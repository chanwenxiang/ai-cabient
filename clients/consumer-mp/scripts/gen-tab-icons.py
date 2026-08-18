from PIL import Image, ImageDraw
import os

OUT = r"c:\Users\cwx\OneDrive\Desktop\demo\ai-cabinet\clients\consumer-mp\src\static\tab"
os.makedirs(OUT, exist_ok=True)
SIZE = 81


def new_canvas():
    return Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))


def save(img, name):
    path = os.path.join(OUT, name)
    img.save(path, "PNG")
    print("wrote", path)


# --- home outline ---
img = new_canvas()
d = ImageDraw.Draw(img)
c = (148, 163, 184, 255)
d.line(
    [(40.5, 18), (18, 38), (24, 38), (24, 62), (57, 62), (57, 38), (63, 38), (40.5, 18)],
    fill=c,
    width=5,
    joint="curve",
)
d.rectangle([36, 46, 45, 62], outline=c, width=4)
save(img, "home.png")

# --- home active ---
img = new_canvas()
d = ImageDraw.Draw(img)
c = (13, 148, 136, 255)
d.polygon([(40.5, 16), (16, 38), (24, 38), (24, 64), (57, 64), (57, 38), (65, 38)], fill=c)
d.rectangle([35, 46, 46, 64], fill=(255, 255, 255, 255))
save(img, "home-active.png")

# --- orders outline ---
img = new_canvas()
d = ImageDraw.Draw(img)
c = (148, 163, 184, 255)
d.rounded_rectangle([22, 16, 59, 66], radius=6, outline=c, width=5)
d.line([(30, 30), (51, 30)], fill=c, width=4)
d.line([(30, 42), (51, 42)], fill=c, width=4)
d.line([(30, 54), (44, 54)], fill=c, width=4)
save(img, "orders.png")

img = new_canvas()
d = ImageDraw.Draw(img)
c = (13, 148, 136, 255)
d.rounded_rectangle([22, 16, 59, 66], radius=6, outline=c, width=5)
d.line([(30, 30), (51, 30)], fill=c, width=4)
d.line([(30, 42), (51, 42)], fill=c, width=4)
d.line([(30, 54), (44, 54)], fill=c, width=4)
save(img, "orders-active.png")

# --- mine outline ---
img = new_canvas()
d = ImageDraw.Draw(img)
c = (148, 163, 184, 255)
d.ellipse([30, 16, 51, 37], outline=c, width=5)
d.arc([20, 42, 61, 72], start=0, end=180, fill=c, width=5)
save(img, "mine.png")

img = new_canvas()
d = ImageDraw.Draw(img)
c = (13, 148, 136, 255)
d.ellipse([30, 16, 51, 37], outline=c, width=5)
d.arc([20, 42, 61, 72], start=0, end=180, fill=c, width=5)
save(img, "mine-active.png")

print("done")
