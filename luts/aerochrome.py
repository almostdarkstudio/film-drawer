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
    # Blue sky / water, dark in infrared: blue clearly above red FOR ITS BRIGHTNESS. (0.59 used plain b - r, which
    # also caught shade-lit white fur and pale stripes, a little bluish: their IR was cut and they went vivid cyan,
    # "the biggest tell"; outside review. White things reflect IR strongly: they go white to pale pink.)
    sky = smoothstep(0.18, 0.42, (b - r) / (b + 0.04))
    # Non-vegetation reflects infrared about as strongly as red light (skin, wood, warm light).
    base = np.maximum(lum, r) * (1.0 - 0.6 * sky)
    # Leaves: an extra term from how green a colour is, ADDED (graded, smaller on surfaces already bright), not a mask
    # with a fixed lift: the foliage pink was "flat and uniform, like a mask", most leaves clipping at full red. Now
    # sunlit leaves go hot pink, mid leaves red-magenta, shaded ones deep crimson, with their texture.
    leaf = 1.0 - np.exp(-20.0 * np.maximum(g - 0.5 * (r + b) - 0.02, 0.0))
    # Red / orange foliage (an acer) still reflects a lot of IR: saturated warm colours get some too, so they go
    # orange-red rather than yellow-olive. Skin is not saturated enough to count.
    warm = smoothstep(0.35, 0.65, r - b)
    ir = base + 0.70 * (1.0 - 0.75 * base) * leaf + 0.5 * (1.0 - 0.6 * base) * warm
    # Soft knee: from 0.75 (slope 1) to white at 1.3, so the brightest leaves roll off instead of clipping flat.
    u = np.clip(ir - 0.75, 0, 0.55)
    ir = np.where(ir > 0.75, 0.75 + u - u * u * (0.55 - 0.25) / (0.55 * 0.55), ir)
    # Visible red -> green, a little less on saturated warm colours, so red foliage leans orange-red, not yellow.
    out = np.stack([ir, (r * 0.90 + 0.02) * (1.0 - 0.25 * warm), g * 0.92 + 0.03 + 0.28 * sky * b], -1)
    # Skin and other non-foliage, non-sky colours: a waxy peach-yellow rather than lemon (review: "teeth and lips look
    # jaundiced"). Of the yellow (red / green average above blue), 35% is lifted into blue and 25% taken off green, so
    # skin leans peach and lips peach-pink, not green-yellow; near-whites barely move. (Half the yellow, the first try, left skin and hair
    # "almost monochrome, a grey-yellow", like a colour-splash edit.)
    w = (1.0 - leaf) * (1.0 - sky) * (1.0 - warm)
    yel = np.maximum(0.5 * (out[..., 0] + out[..., 1]) - out[..., 2], 0.0)
    out[..., 1] -= 0.25 * w * yel
    out[..., 2] += 0.35 * w * yel
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
