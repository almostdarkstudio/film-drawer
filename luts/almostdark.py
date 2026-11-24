"""Almost Dark: a low-light "cine" look by Almost Dark Studio. Part of Film Drawer (MIT licence).

Usage: python -I almostdark.py <vision500t.lut> <almostdark.lut>

Derived from the Vision3 500T look (EmulsionPresets by peva3, MIT). It is not an emulation of a stock.
Vision3 500T's tones are kept (its shadows already fall to black like the photographer's own edits), with
  - a soft floor (~7) instead of pure black, like the photographer's film scans and edits;
  - brighter upper mids (lit surfaces stand out from the dark), shadows unchanged;
  - denser colour, warm hues most (lamplight on brick), easing off for already strong colours and, for
    warm hues, in brighter tones so skin does not go orange;
  - near-neutral cream whites. No halation.
"""
import sys

import numpy as np

W = np.array([.2126, .7152, .0722])


def almostdark(c):
    L = (c @ W)[..., None]
    u = np.clip((L - .18) / .82, 0, 1)
    k = (L + .062 * 6.75 * u ** 2 * (1 - u)) / np.maximum(L, 1e-4)    # upper mids up by at most ~16/255
    c = np.clip(c * k, 0, 1)
    L = (c @ W)[..., None]
    mx, mn = c.max(-1), c.min(-1)
    sat = ((mx - mn) / np.maximum(mx, 1e-3))[..., None]
    r, g, b = c[..., 0], c[..., 1], c[..., 2]
    d = np.maximum(mx - mn, 1e-6)
    h = np.where(mx == r, ((g - b) / d) % 6, np.where(mx == g, (b - r) / d + 2, (r - g) / d + 4)) * 60

    def band(lo, hi, soft):
        return np.clip((h - lo + soft) / soft, 0, 1) * np.clip((hi + soft - h) / soft, 0, 1)

    warm = np.maximum(band(8, 55, 18), band(352, 360, 18))[..., None]
    skin = np.clip((L - .30) / .25, 0, 1) * warm                    # brighter warm tones: ease off
    mid = np.clip((L - .04) / .12, 0, 1) * np.clip((1 - L) / .12, 0, 1)
    out = L + (c - L) * (1 + mid * (0.80 + 0.60 * warm) * (1 - .75 * sat) * (1 - .80 * skin))
    out *= 1 - 0.08 * mid * sat                                     # dense colours print darker
    out *= 1 + mid * sat * warm * (1 - .8 * skin) * np.array([.03, 0, -.10])
    hi = np.clip((L - .6) / .4, 0, 1) ** 1.5
    out *= 1 + hi * np.array([-.014, -.016, -.045])
    out = np.clip(out, 0, 1)
    fl = 7 / 255.
    L2 = (out @ W)[..., None]
    return np.clip(out + (np.sqrt(L2 ** 2 + fl ** 2) - L2), 0, 1)


def main(src, dst):
    raw = open(src, "rb").read()
    n = raw[4]
    cube = np.frombuffer(raw[5:], np.uint8).reshape(n, n, n, 3).astype(np.float64) / 255
    data = np.clip(np.rint(almostdark(cube) * 255), 0, 255).astype(np.uint8)
    with open(dst, "wb") as f:
        f.write(raw[:5] + data.tobytes())
    step = max(int(np.abs(np.diff(data.astype(int), axis=a)).max()) for a in range(3))
    print(f"wrote {dst} (max grid step {step})")


if __name__ == "__main__":
    main(*sys.argv[1:3])
