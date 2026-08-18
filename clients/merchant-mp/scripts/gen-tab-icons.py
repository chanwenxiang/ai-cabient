from PIL import Image, ImageDraw
import os

OUT = r"c:\Users\cwx\OneDrive\Desktop\demo\ai-cabinet\clients\merchant-mp\src\static\tab"
os.makedirs(OUT, exist_ok=True)
SIZE = 81
MUTED = (148, 163, 184, 255)
ACTIVE = (13, 148, 136, 255)


def new_canvas():
    return Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))


def save(img, name):
    path = os.path.join(OUT, name)
    img.save(path, "PNG")
    print("wrote", path)


def draw_home(color, filled=False):
    img = new_canvas()
    d = ImageDraw.Draw(img)
    if filled:
        d.polygon([(40.5, 16), (16, 38), (24, 38), (24, 64), (57, 64), (57, 38), (65, 38)], fill=color)
        d.rectangle([35, 46, 46, 64], fill=(255, 255, 255, 255))
    else:
        d.line(
            [(40.5, 18), (18, 38), (24, 38), (24, 62), (57, 62), (57, 38), (63, 38), (40.5, 18)],
            fill=color,
            width=5,
            joint="curve",
        )
        d.rectangle([36, 46, 45, 62], outline=color, width=4)
    return img


def draw_devices(color):
    img = new_canvas()
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([24, 14, 57, 66], radius=6, outline=color, width=5)
    d.line([(32, 28), (49, 28)], fill=color, width=4)
    d.line([(32, 40), (49, 40)], fill=color, width=4)
    d.line([(32, 52), (42, 52)], fill=color, width=4)
    return img


def draw_alerts(color):
    img = new_canvas()
    d = ImageDraw.Draw(img)
    # bell body
    d.arc([22, 18, 59, 52], start=200, end=340, fill=color, width=5)
    d.line([(22, 42), (22, 50)], fill=color, width=5)
    d.line([(59, 42), (59, 50)], fill=color, width=5)
    d.line([(20, 50), (61, 50)], fill=color, width=5)
    d.arc([34, 50, 47, 64], start=0, end=180, fill=color, width=4)
    return img


def draw_mine(color):
    img = new_canvas()
    d = ImageDraw.Draw(img)
    d.ellipse([30, 16, 51, 37], outline=color, width=5)
    d.arc([20, 42, 61, 72], start=0, end=180, fill=color, width=5)
    return img


save(draw_home(MUTED), "home.png")
save(draw_home(ACTIVE, filled=True), "home-active.png")
save(draw_devices(MUTED), "devices.png")
save(draw_devices(ACTIVE), "devices-active.png")
save(draw_alerts(MUTED), "alerts.png")
save(draw_alerts(ACTIVE), "alerts-active.png")
save(draw_mine(MUTED), "mine.png")
save(draw_mine(ACTIVE), "mine-active.png")
# keep orders icons consistent if referenced elsewhere
save(draw_devices(MUTED), "orders.png")
save(draw_devices(ACTIVE), "orders-active.png")
print("done")
