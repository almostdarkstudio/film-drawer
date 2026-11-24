"""Bake Lightroom/ACR (.xmp) colour presets into 33^3 LUTs (FLUT format) for camera JPEGs.

APPROXIMATION: re-implements the per-pixel parts of a preset (exposure, contrast, highlights/
shadows/whites/blacks, point curves, HSL, saturation/vibrance, colour grading) with simple,
documented maths. It does NOT reproduce Adobe's exact processing. Spatial effects (clarity,
texture, dehaze, sharpening, grain, vignette) cannot live in a LUT and are ignored. The camera
JPEG is taken as already rendered, so the preset's base profile ("Adobe Color") is identity.

Usage: python -I xmp2lut.py <presets_dir> <list.txt> <out_dir> [sample.jpg]
list.txt lines: <id> | <TAG> | <menu name> | <path relative to presets_dir>
"""
import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

N = 33

# ---------------------------------------------------------------- XMP parsing


def parse_xmp(text):
    s = {}
    for k, v in re.findall(r'crs:([A-Za-z0-9]+)="([^"]*)"', text):
        try:
            s[k] = float(v)
        except ValueError:
            s[k] = v
    for name in ("ToneCurvePV2012", "ToneCurvePV2012Red", "ToneCurvePV2012Green", "ToneCurvePV2012Blue"):
        m = re.search(r"<crs:%s>(.*?)</crs:%s>" % (name, name), text, re.S)
        if m:
            pts = [tuple(float(x) for x in p.split(",")) for p in re.findall(r"<rdf:li>([^<]+)</rdf:li>", m.group(1))]
            s[name] = pts
    return s


# ---------------------------------------------------------------- colour helpers


def srgb_to_lin(c):
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def lin_to_srgb(c):
    c = np.clip(c, 0, None)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1 / 2.4) - 0.055)


def luma(rgb):
    return rgb[..., 0] * 0.2126 + rgb[..., 1] * 0.7152 + rgb[..., 2] * 0.0722


def rgb_to_hsv(rgb):
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    mx, mn = rgb.max(-1), rgb.min(-1)
    d = mx - mn
    h = np.zeros_like(mx)
    nz = d > 1e-9
    rc = np.where(nz, (mx - r) / np.where(nz, d, 1), 0)
    gc = np.where(nz, (mx - g) / np.where(nz, d, 1), 0)
    bc = np.where(nz, (mx - b) / np.where(nz, d, 1), 0)
    h = np.where(r == mx, bc - gc, np.where(g == mx, 2 + rc - bc, 4 + gc - rc))
    h = np.where(nz, (h / 6) % 1, 0)
    s = np.where(mx > 1e-9, d / np.where(mx > 1e-9, mx, 1), 0)
    return h * 360, s, mx


def hsv_to_rgb(h, s, v):
    h = (h % 360) / 60
    i = np.floor(h).astype(int) % 6
    f = h - np.floor(h)
    p, q, t = v * (1 - s), v * (1 - s * f), v * (1 - s * (1 - f))
    out = np.stack([
        np.choose(i, [v, q, p, p, t, v]),
        np.choose(i, [t, v, v, q, p, p]),
        np.choose(i, [p, p, t, v, v, q]),
    ], -1)
    return out


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def pchip(xs, ys, x):
    """Monotone cubic interpolation (for Lightroom point curves, 0..255)."""
    xs, ys = np.asarray(xs, float), np.asarray(ys, float)
    if len(xs) == 2:
        return np.interp(x, xs, ys)
    h = np.diff(xs)
    dlt = np.diff(ys) / h
    m = np.zeros_like(ys)
    for k in range(1, len(xs) - 1):
        if dlt[k - 1] * dlt[k] > 0:
            w1, w2 = 2 * h[k] + h[k - 1], h[k] + 2 * h[k - 1]
            m[k] = (w1 + w2) / (w1 / dlt[k - 1] + w2 / dlt[k])
    m[0], m[-1] = dlt[0], dlt[-1]
    k = np.clip(np.searchsorted(xs, x) - 1, 0, len(xs) - 2)
    t = (x - xs[k]) / h[k]
    h00, h10, h01, h11 = 2 * t**3 - 3 * t**2 + 1, t**3 - 2 * t**2 + t, -2 * t**3 + 3 * t**2, t**3 - t**2
    return h00 * ys[k] + h10 * h[k] * m[k] + h01 * ys[k + 1] + h11 * h[k] * m[k + 1]


# ---------------------------------------------------------------- the preset model

BANDS = [("Red", 0), ("Orange", 30), ("Yellow", 60), ("Green", 120),
         ("Aqua", 180), ("Blue", 240), ("Purple", 270), ("Magenta", 300)]


def band_weights(h):
    """Smooth partition of unity over the 8 Lightroom hue bands (h in degrees)."""
    cs = [c for _, c in BANDS] + [360]
    w = np.zeros(h.shape + (8,))
    for i in range(8):
        c0, c1 = cs[i], cs[i + 1]
        t = np.clip(((h - c0) % 360) / (c1 - c0), 0, None)
        inside = ((h - c0) % 360) < (c1 - c0)
        a = 0.5 + 0.5 * np.cos(np.pi * np.clip(t, 0, 1))        # 1 at c0 -> 0 at c1
        w[..., i] += np.where(inside, a, 0)
        w[..., (i + 1) % 8] += np.where(inside, 1 - a, 0)
    return w


def apply_preset(s, rgb):
    """rgb: sRGB-encoded floats 0..1, shape (..., 3). Returns sRGB floats 0..1."""
    g = lambda k: float(s.get(k, 0) or 0)

    # 1. Exposure (stops), in linear light.
    lin = srgb_to_lin(rgb) * (2 ** g("Exposure2012"))
    x = lin_to_srgb(lin)

    # 2. Basic tone on luminance (hue-preserving ratio), working on perceptual (sRGB-encoded) values.
    L = np.clip(luma(x), 0, None)
    Lt = L.copy()
    c = g("Contrast2012") / 100
    Lt = Lt + c * 0.35 * (Lt - 0.5) * (1 - np.abs(2 * Lt - 1)) * 2        # S-curve around mid grey
    Lt = Lt + g("Highlights2012") / 100 * 0.22 * smoothstep(0.45, 1.0, Lt) * (1 - Lt * 0.5)
    Lt = Lt + g("Shadows2012") / 100 * 0.22 * (1 - smoothstep(0.0, 0.55, Lt)) * np.clip(Lt * 4, 0, 1)
    Lt = Lt + g("Whites2012") / 100 * 0.12 * Lt ** 4
    Lt = Lt + g("Blacks2012") / 100 * 0.10 * (1 - Lt) ** 4
    Lt = np.clip(Lt, 0, 1.2)
    # Hue-preserving ratio for mid/bright tones; near black the ratio explodes for dark saturated
    # colours (e.g. 0,0,7 -> bright blue), so there the change is added equally to R, G and B.
    ratio = np.minimum(Lt / np.maximum(L, 1e-4), 2.0)
    w = smoothstep(0.04, 0.25, L)[..., None]
    x = w * (x * ratio[..., None]) + (1 - w) * (x + (Lt - L)[..., None])

    # 3. Point curves (0..255), master then per channel.
    def curve(name, v):
        pts = s.get(name)
        if not pts or pts == [(0, 0), (255, 255)]:
            return v
        xs, ys = zip(*sorted(pts))
        return pchip(xs, ys, np.clip(v, 0, 1) * 255) / 255
    x = curve("ToneCurvePV2012", x)
    x = np.stack([curve("ToneCurvePV2012Red", x[..., 0]), curve("ToneCurvePV2012Green", x[..., 1]),
                  curve("ToneCurvePV2012Blue", x[..., 2])], -1)
    x = np.clip(x, 0, 1)

    # 4. HSL per band, then global saturation / vibrance.
    h, sat, v = rgb_to_hsv(x)
    w = band_weights(h)
    hue_adj = sum(w[..., i] * g("HueAdjustment" + n) for i, (n, _) in enumerate(BANDS))
    sat_adj = sum(w[..., i] * g("SaturationAdjustment" + n) for i, (n, _) in enumerate(BANDS))
    lum_adj = sum(w[..., i] * g("LuminanceAdjustment" + n) for i, (n, _) in enumerate(BANDS))
    h = h + hue_adj / 100 * 30 * np.clip(sat * 3, 0, 1)                    # +-100 ~ +-30 degrees
    sat = sat * (1 + sat_adj / 100)
    v = v * (1 + lum_adj / 100 * 0.35 * np.clip(sat * 2, 0, 1))
    sat = sat * (1 + g("Saturation") / 100)
    sat = sat * (1 + g("Vibrance") / 100 * (1 - np.clip(sat, 0, 1)) * 0.8)
    x = hsv_to_rgb(h, np.clip(sat, 0, 1), np.clip(v, 0, 1.2))

    # 5. Colour grading: tints by luminance zone (shadows / midtones / highlights / global).
    L = np.clip(luma(x), 0, 1)
    bal = g("ColorGradeBalance") / 100                       # -100..100: shifts the shadow/highlight split
    blend = max(g("ColorGradeBlending"), 1) / 100            # 0..100: overlap of zones
    mid = 0.5 - bal * 0.25
    width = 0.15 + 0.35 * blend
    ws = 1 - smoothstep(mid - width, mid + width * 0.5, L)
    wh = smoothstep(mid - width * 0.5, mid + width, L)
    wm = np.clip(1 - ws - wh, 0, 1) + 0.0
    zones = [("Shadow", ws), ("Midtone", wm), ("Highlight", wh), ("Global", np.ones_like(L))]
    add = np.zeros_like(x)
    for zname, wz in zones:
        hz, sz, lz = g("ColorGrade%sHue" % zname), g("ColorGrade%sSat" % zname), g("ColorGrade%sLum" % zname)
        if sz:
            tint = hsv_to_rgb(np.full(L.shape, hz), np.ones_like(L), np.ones_like(L))
            chroma = tint - luma(tint)[..., None]             # zero-luminance colour direction
            add += (wz * sz / 100 * 0.22)[..., None] * chroma
        if lz:
            add += (wz * lz / 100 * 0.15)[..., None]
    x = x + add
    return np.clip(x, 0, 1)


# ---------------------------------------------------------------- output


def bake(s):
    gr = np.linspace(0, 1, N)
    b, gg, r = np.meshgrid(gr, gr, gr, indexing="ij")
    rgb = np.stack([r, gg, b], -1)
    out = apply_preset(s, rgb)
    return np.clip(np.rint(out * 255), 0, 255).astype(np.uint8)


def tetra(cube, img):
    n = cube.shape[0]
    s = n - 1
    pos = img / 255.0 * s
    i = np.minimum(np.floor(pos).astype(np.int32), s - 1)
    f = pos - i
    r0, g0, b0 = i[..., 0], i[..., 1], i[..., 2]
    fr, fg, fb = f[..., 0:1], f[..., 1:2], f[..., 2:3]
    c = lambda dr, dg, db: cube[b0 + db, g0 + dg, r0 + dr].astype(np.float32)
    c000, c111 = c(0, 0, 0), c(1, 1, 1)
    res = np.zeros(img.shape, np.float32)
    for m, fn in [
        ((fr >= fg) & (fg >= fb), lambda: c000 + fr * (c(1, 0, 0) - c000) + fg * (c(1, 1, 0) - c(1, 0, 0)) + fb * (c111 - c(1, 1, 0))),
        ((fr >= fg) & (fg < fb) & (fr >= fb), lambda: c000 + fr * (c(1, 0, 0) - c000) + fb * (c(1, 0, 1) - c(1, 0, 0)) + fg * (c111 - c(1, 0, 1))),
        ((fr >= fg) & (fr < fb), lambda: c000 + fb * (c(0, 0, 1) - c000) + fr * (c(1, 0, 1) - c(0, 0, 1)) + fg * (c111 - c(1, 0, 1))),
        ((fr < fg) & (fb >= fg), lambda: c000 + fb * (c(0, 0, 1) - c000) + fg * (c(0, 1, 1) - c(0, 0, 1)) + fr * (c111 - c(0, 1, 1))),
        ((fr < fg) & (fb < fg) & (fb >= fr), lambda: c000 + fg * (c(0, 1, 0) - c000) + fb * (c(0, 1, 1) - c(0, 1, 0)) + fr * (c111 - c(0, 1, 1))),
        ((fr < fg) & (fb < fg) & (fb < fr), lambda: c000 + fg * (c(0, 1, 0) - c000) + fr * (c(1, 1, 0) - c(0, 1, 0)) + fb * (c111 - c(1, 1, 0))),
    ]:
        res = np.where(m, fn(), res)
    return np.clip(np.rint(res), 0, 255).astype(np.uint8)


def main(presets_dir, list_path, out_dir, sample=None):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    rows = []
    for line in Path(list_path).read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            lid, tag, name, rel = [p.strip() for p in line.split("|")]
            rows.append((lid, tag, name, rel))

    tiles = []
    base = None
    if sample:
        im = Image.open(sample).convert("RGB")
        im.thumbnail((560, 400), Image.LANCZOS)
        base = np.asarray(im)
        tiles.append(("Original", base))
    for lid, tag, name, rel in rows:
        s = parse_xmp((Path(presets_dir) / rel).read_text(encoding="utf-8"))
        cube = bake(s)
        (out / f"{lid}.lut").write_bytes(b"FLUT" + bytes([N]) + cube.tobytes())
        # Smoothness check: largest jump between neighbouring grid points (banding/posterising risk).
        jump = max(np.abs(np.diff(cube.astype(int), axis=a)).max() for a in range(3))
        ignored = [k for k in ("Clarity2012", "Texture", "Dehaze", "GrainAmount", "PostCropVignetteAmount")
                   if float(s.get(k, 0) or 0) != 0]
        print(f"{lid:12s} {tag}  max grid step {jump:3d}  ignored (spatial): {', '.join(ignored) or '-'}")
        if base is not None:
            tiles.append((name, tetra(cube, base.astype(np.float32))))
    if tiles:
        th, tw = tiles[0][1].shape[:2]
        cols, lab = 3, 22
        rws = -(-len(tiles) // cols)
        sheet = Image.new("RGB", (cols * (tw + 10) + 10, rws * (th + lab + 10) + 10), (30, 30, 30))
        d = ImageDraw.Draw(sheet)
        for i, (nm, arr) in enumerate(tiles):
            x, y = 10 + (i % cols) * (tw + 10), 10 + (i // cols) * (th + lab + 10)
            d.text((x, y + 4), nm, fill=(235, 235, 235))
            sheet.paste(Image.fromarray(arr), (x, y + lab))
        sheet.save(out / "preview.jpg", quality=90)
        print("wrote", out / "preview.jpg")


if __name__ == "__main__":
    main(*sys.argv[1:5])
