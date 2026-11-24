"""Draws the Film Drawer icon (a street lamp at night inside a film frame). Part of Film Drawer (MIT licence).

Usage: python -I make-icon.py <launcher 96x96 png> [<large 240x240 png>]
"""
import sys

import numpy as np
from PIL import Image, ImageDraw

N, K = 96, 8                 # 96x96 (as Sony's own launcher icons), drawn at 8x
Z = N * K
yy, xx = np.mgrid[:Z, :Z] / K


def picture(a):
    return Image.fromarray(np.clip(np.rint(a), 0, 255).astype(np.uint8), "RGB").convert("RGBA")


def fill(im, colour, draw):
    m = Image.new("L", (Z, Z), 0)
    draw(ImageDraw.Draw(m))
    im.paste(Image.new("RGBA", im.size, colour + (255,)), (0, 0), m)


def rounded(d, x0, y0, x1, y1, r):
    d.rounded_rectangle([x0 * K, y0 * K, x1 * K - 1, y1 * K - 1], r * K, fill=255)


def glow(a, cx, cy, r, colour):
    g = np.exp(-(np.hypot(xx - cx, yy - cy) / r) ** 1.3)[..., None]
    return 255 - (255 - a) * (1 - np.clip(g * np.array(colour) / 255, 0, 1))


def draw_icon():
    night = [(0, (9, 11, 22)), (60, (20, 22, 36)), (96, (8, 8, 12))]
    a = np.stack([np.interp(yy, [s[0] for s in night], [s[1][c] for s in night]) for c in range(3)], -1)
    a = glow(a, 50, 37, 25, (255, 165, 50))
    a = glow(a, 50, 37, 9, (255, 225, 160))
    im = picture(a)

    def post(d):
        d.rectangle([68 * K, 30 * K, 72 * K, 96 * K], fill=255)
        d.arc([50 * K, 18 * K, 72 * K, 42 * K], 180, 360, fill=255, width=4 * K)

    fill(im, (5, 5, 8), post)
    fill(im, (5, 5, 8), lambda d: d.polygon([(44 * K, 36 * K), (49 * K, 28 * K), (55 * K, 28 * K), (60 * K, 36 * K)], fill=255))
    fill(im, (255, 246, 222), lambda d: d.pieslice([44.5 * K, 28 * K, 59.5 * K, 44 * K], 0, 180, fill=255))

    def rebate(d):
        d.rectangle([0, 0, 20 * K, Z], fill=255)
        d.rectangle([76 * K, 0, Z, Z], fill=255)

    def perforations(d):
        for i in range(5):
            y = 10.5 + i * 16.2
            rounded(d, 7, y, 15, y + 10, 2.4)
            rounded(d, 81, y, 89, y + 10, 2.4)

    fill(im, (6, 6, 9), rebate)
    fill(im, (214, 140, 40), perforations)
    m = Image.new("L", (Z, Z), 0)
    rounded(ImageDraw.Draw(m), 2, 2, 94, 94, 16)
    im.putalpha(m)
    edge = Image.new("RGBA", im.size, (0, 0, 0, 0))
    ImageDraw.Draw(edge).rounded_rectangle([2 * K, 2 * K, 94 * K - 1, 94 * K - 1], 16 * K, outline=(255, 255, 255, 46), width=K)
    return Image.alpha_composite(im, edge)


if __name__ == "__main__":
    icon = draw_icon()
    icon.resize((N, N), Image.LANCZOS).save(sys.argv[1], optimize=True)
    if len(sys.argv) > 2:
        icon.resize((240, 240), Image.LANCZOS).save(sys.argv[2], optimize=True)
