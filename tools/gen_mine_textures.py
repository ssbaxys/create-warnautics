"""Generate Minecraft 16x16 land-mine textures (Pillow only — no AI images)."""
from PIL import Image, ImageDraw
import os

OUT = os.path.join(
    os.path.dirname(__file__),
    "..",
    "src",
    "main",
    "resources",
    "assets",
    "cbc_more_content",
    "textures",
    "block",
)


def px(img, x, y, rgba):
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), rgba)


def fill_ellipse(img, cx, cy, rx, ry, color, ring=None):
    for y in range(cy - ry, cy + ry + 1):
        for x in range(cx - rx, cx + rx + 1):
            dx = (x - cx) / max(rx, 1)
            dy = (y - cy) / max(ry, 1)
            if dx * dx + dy * dy <= 1.0:
                px(img, x, y, color)
            elif ring and dx * dx + dy * dy <= 1.15:
                px(img, x, y, ring)


def make_top(kind: str) -> Image.Image:
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    if kind == "small":
        rim = (38, 46, 28, 255)
        body = (58, 74, 40, 255)
        highlight = (78, 96, 52, 255)
        fuze = (110, 112, 78, 255)
        pin = (150, 150, 110, 255)
        fill_ellipse(img, 7, 7, 6, 6, body, rim)
        fill_ellipse(img, 7, 7, 4, 4, highlight)
        fill_ellipse(img, 7, 7, 2, 2, fuze)
        px(img, 7, 7, pin)
        for bx, by in ((2, 2), (12, 2), (2, 12), (12, 12)):
            px(img, bx, by, (28, 32, 22, 255))
            px(img, bx + 1, by, (90, 98, 70, 255))
    else:
        rim = (28, 30, 26, 255)
        body = (52, 58, 46, 255)
        highlight = (68, 74, 58, 255)
        fuze = (72, 76, 64, 255)
        red = (150, 36, 32, 255)
        fill_ellipse(img, 7, 7, 7, 7, body, rim)
        fill_ellipse(img, 7, 7, 5, 5, highlight)
        fill_ellipse(img, 7, 7, 3, 3, fuze)
        for x in range(3, 13):
            px(img, x, 7, red)
            px(img, x, 8, (110, 28, 24, 255))
        px(img, 7, 7, (180, 50, 40, 255))
        for bx, by in ((1, 1), (13, 1), (1, 13), (13, 13)):
            px(img, bx, by, (20, 22, 18, 255))
            px(img, bx + 1, by, (80, 84, 70, 255))
    return img


def make_side(kind: str) -> Image.Image:
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if kind == "small":
        d.rectangle((0, 11, 15, 15), fill=(38, 46, 28, 255))
        d.rectangle((0, 12, 15, 14), fill=(58, 74, 40, 255))
        d.rectangle((0, 13, 15, 13), fill=(110, 112, 78, 255))
    else:
        d.rectangle((0, 9, 15, 15), fill=(28, 30, 26, 255))
        d.rectangle((0, 10, 15, 14), fill=(52, 58, 46, 255))
        d.rectangle((0, 12, 15, 12), fill=(150, 36, 32, 255))
    return img


def main():
    os.makedirs(OUT, exist_ok=True)
    make_top("small").save(os.path.join(OUT, "small_mine.png"))
    make_side("small").save(os.path.join(OUT, "small_mine_side.png"))
    make_top("large").save(os.path.join(OUT, "large_mine.png"))
    make_side("large").save(os.path.join(OUT, "large_mine_side.png"))
    print("Wrote Python mine textures to", os.path.abspath(OUT))


if __name__ == "__main__":
    main()
