"""Original Aerochrome-style (colour infrared) look for Film Drawer. Part of Film Drawer (MIT licence).

Kodak Aerochrome is a false-colour infrared film: with the usual yellow filter its three layers
record infrared, red and green, and show them as red, green and blue. So foliage (very bright in
near-infrared) turns red/magenta, skies and water go deep blue, skin goes pale.

A normal camera records no infrared, so it is estimated per pixel from the visible colour:
vegetation (green clearly above red and blue) reflects strongly, most other surfaces roughly with
their brightness, and blue sky/water very little. Then
    out.R = IR,  out.G = visible red,  out.B = visible green
plus a gentle contrast curve. Everything is smooth in RGB, so the 33^3 LUT has no hard edges.

Usage: python -I aerochrome.py <out.lut>
"""
import sys

import numpy as np

N = 33


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def aerochrome(r, g, b):
    lum = 0.30 * r + 0.59 * g + 0.11 * b
    # Vegetation index from visible light: how much green stands above red and blue.
    veg = smoothstep(0.0, 0.18, g - 0.5 * (r + b) - 0.02)
    # Blue sky / water: blue clearly above red, dark in infrared.
    sky = smoothstep(0.02, 0.25, b - r)
    # Non-vegetation reflects infrared about as strongly as red light (skin, wood, warm light), so
    # skin and reds come out pale yellow/peach as on the real film, not green.
    # (IR a little above the red channel keeps skin peach rather than lime.)
    base = np.maximum(lum, 1.06 * r)
    ir = base * (1.0 - 0.6 * sky) + veg * (0.35 + 0.9 * g)
    ir = np.clip(ir, 0, 1)
    out = np.stack([ir, r * 0.90 + 0.02, g * 0.92 + 0.03 + 0.28 * sky * b], -1)   # deeper blue skies
    # Aerochrome: punchy, slightly crushed shadows, clean highlights.
    out = np.clip(out, 0, 1)
    out = out + 0.10 * np.sin(np.pi * (out - 0.5)) * 0.9
    return np.clip(out, 0, 1)


def main(path):
    v = np.linspace(0, 1, N)
    bb, gg, rr = np.meshgrid(v, v, v, indexing="ij")          # [b, g, r]: red fastest
    out = aerochrome(rr, gg, bb)
    data = np.clip(np.rint(out * 255), 0, 255).astype(np.uint8)
    with open(path, "wb") as f:
        f.write(b"FLUT" + bytes([N]) + data.tobytes())
    step = np.abs(np.diff(data.astype(int), axis=2)).max()
    print(f"wrote {path} (max grid step {step})")


if __name__ == "__main__":
    main(sys.argv[1])
