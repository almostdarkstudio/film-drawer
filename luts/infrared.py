"""Original black-and-white infrared look for Film Drawer (in the spirit of Kodak HIE / Rollei Infrared through a deep
red filter). Part of Film Drawer (MIT licence).

Black-and-white infrared film behind an R72-type filter records mostly near-infrared: foliage reflects it strongly and
glows white (the Wood effect), blue sky and water reflect almost none and go nearly black, skin is pale and luminous.

A normal camera records no infrared, so it is estimated per pixel from the visible colour, as in aerochrome.py:
vegetation (green clearly above red and blue) reflects strongly, most other surfaces roughly with their red, and blue
sky / water very little. The output is neutral grey (every LUT entry R = G = B), so the app treats it as a black and
white look (its grain curve and scale). Then HIE's contrast: deep blacks, a soft top. Everything is smooth in RGB, so
the 33^3 LUT has no hard edges.

Usage: python -I infrared.py <out.lut>
"""
import sys

import numpy as np

N = 33


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def infrared(r, g, b):
    lum = 0.30 * r + 0.59 * g + 0.11 * b
    sky = smoothstep(0.02, 0.22, b - 0.5 * (r + g))                 # blue sky / water: dark in infrared
    # Skin, wood, warm light: about their red (0.92: a little more shoulder on bright skin; review: cheeks near clipping)
    base = np.maximum(lum, 0.92 * r) * (1.0 - 0.80 * sky)
    # Leaves reflect infrared several times more than they reflect green: an extra term from how green a colour is
    # (green above red and blue, past a small dead zone for near-neutrals: 0.012; 0.03 left a dark hedge in deep shade,
    # around 10 in the camera's JPEG, black; review), steep, so leaves in shade come out mid grey
    # too, and smaller on surfaces already bright, so sunlit foliage keeps its texture. ADDED to the base, not a mask
    # that switches between a leaf formula and a non-leaf one: a blurred edge between a pale blob and a dark leaf is a
    # mix of the two colours, and the mask counted the mix as "not quite leaf", darker than both sides, which drew
    # dark contour lines round every out-of-focus blob (outside review: "topographic lines"). Added, a mix lands
    # between its two ends.
    e = np.maximum(g - 0.5 * (r + b) - 0.012, 0.0)
    ir = base + 0.65 * (1.0 - 0.80 * base) * (1.0 - np.exp(-22.0 * e))
    y = 0.88 * ir + 0.12 * r * (1.0 - sky)                          # a little visible red through the filter
    # Leaves can add past white: a soft knee from 0.7 (slope 1 there) that reaches white at 1.2, so the brightest
    # foliage rolls off rather than clipping flat.
    u = np.clip(y - 0.7, 0, 0.5)
    y = np.where(y > 0.7, 0.7 + u - u * u * (0.5 - 0.3) / 0.25, y)
    y = np.clip(y, 0, 1)
    # HIE: contrasty, but not a threshold: a mild S-curve, then a shoulder so faces and bright leaves keep detail.
    y = np.clip(y + 0.07 * np.sin(np.pi * (y - 0.5)), 0, 1)
    t = smoothstep(0.55, 1.0, y)
    y = y - 0.45 * (1.0 - y) * t
    return np.clip(y, 0, 1)


def main(path):
    v = np.linspace(0, 1, N)
    bb, gg, rr = np.meshgrid(v, v, v, indexing="ij")          # [b, g, r]: red fastest
    y = infrared(rr, gg, bb)
    data = np.clip(np.rint(np.stack([y, y, y], -1) * 255), 0, 255).astype(np.uint8)
    with open(path, "wb") as f:
        f.write(b"FLUT" + bytes([N]) + data.tobytes())
    step = np.abs(np.diff(data.astype(int), axis=2)).max()
    print(f"wrote {path} (max grid step {step})")


if __name__ == "__main__":
    main(sys.argv[1])
