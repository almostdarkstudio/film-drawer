"""Assemble the app's assets/luts from both LUT sources, in the order given by app-looks.txt.

Usage: python -I assemble.py <luts_dir> <app_assets_luts_dir>
Sources: looks.txt -> out/ (RawTherapee HaldCLUTs), emulsion-trial.txt -> out-emulsion/ (EmulsionPresets).
"""
import sys
from pathlib import Path

import numpy as np


def table(path):
    rows = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            lid, tag, name, _src = [p.strip() for p in line.split("|")]
            rows[lid] = (tag, name)
    return rows


def read_tweaks(path):
    out = {}
    if Path(path).exists():
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            if line.strip() and not line.lstrip().startswith("#"):
                parts = line.split()
                out[parts[0]] = {k: float(v) for k, v in (p.split("=") for p in parts[1:])}
    return out


def hue_sat_tweak(cube, yellow=1.0, green=1.0):
    """Hue-selective saturation on LUT outputs (uint8 cube): scales chroma around luma for yellows
    (~35-75 deg) and greens (~75-160 deg) with smooth edges. Factors < 1 mute those hues."""
    c = cube.astype(np.float32) / 255
    r, g, b = c[..., 0], c[..., 1], c[..., 2]
    mx, mn = c.max(-1), c.min(-1)
    d = np.where(mx - mn < 1e-6, 1e-6, mx - mn)
    h = np.where(mx == r, ((g - b) / d) % 6, np.where(mx == g, (b - r) / d + 2, (r - g) / d + 4)) * 60
    sat = (mx - mn) / np.maximum(mx, 1e-6)

    def band(lo, hi, soft):
        return np.clip((h - lo + soft) / soft, 0, 1) * np.clip((hi + soft - h) / soft, 0, 1)

    w_y, w_g = band(40, 70, 12), band(80, 155, 15)
    f = 1 - (1 - yellow) * w_y - (1 - green) * w_g
    f = 1 - (1 - f) * np.clip(sat * 3, 0, 1)               # leave near-greys alone
    luma = (0.299 * r + 0.587 * g + 0.114 * b)[..., None]
    out = luma + (c - luma) * f[..., None]
    return np.clip(np.rint(out * 255), 0, 255).astype(np.uint8)


def toe(cube, toe_r=0.0, toe_g=0.0, toe_b=0.0, toe_p=2.5):
    """Deepens lifted blacks on LUT outputs (uint8 cube): lowers each channel by its toe amount at the
    look's own black, fading out towards its white ((white - x) / (white - black)) ** toe_p, so mids are
    barely touched and highlights not at all."""
    c = cube.astype(np.float32)
    blk, wht = c[0, 0, 0], c[-1, -1, -1]
    f = np.clip((wht - c) / np.maximum(wht - blk, 1), 0, 1) ** toe_p
    out = c - np.array([toe_r, toe_g, toe_b], np.float32) * f
    return np.clip(np.rint(out), 0, 255).astype(np.uint8)


def keep_hue(cube, amount):
    """Looks with a capped red channel push bright oranges toward yellow. Turns warm inputs (hue ~5-50)
    back toward their own hue, keeping the look's luma and (up to 1.15x the input's) chroma strength; fades in with brightness."""
    n = cube.shape[0]
    g = np.linspace(0, 1, n, dtype=np.float32)
    b_, g_, r_ = np.meshgrid(g, g, g, indexing="ij")
    src = np.stack([r_, g_, b_], -1)
    c = cube.astype(np.float32) / 255
    w = np.array([0.299, 0.587, 0.114], np.float32)
    ys, y = (src @ w)[..., None], (c @ w)[..., None]
    ds, dc = src - ys, c - y
    ms, mc = np.linalg.norm(ds, axis=-1, keepdims=True), np.linalg.norm(dc, axis=-1, keepdims=True)
    mx, mn = src.max(-1), src.min(-1)
    d = np.maximum(mx - mn, 1e-6)
    h = np.where(mx == r_, (g_ - b_) / d, np.where(mx == g_, (b_ - r_) / d + 2, 9)) * 60
    band = np.clip((h + 8) / 20, 0, 1) * np.clip((90 - h) / 32, 0, 1)
    k = amount * band[..., None] * np.clip((y - 0.30) / 0.30, 0, 1) * np.clip(ms / 0.08, 0, 1)
    mt = np.minimum(mc, 1.15 * ms)                                # no more than a little over the original
    d_new = dc * (1 - k) + ds / np.maximum(ms, 1e-6) * mt * k
    out = y + d_new
    # Stay under the look's own white (pull chroma in rather than clip one channel and shift hue again).
    cap = float(cube[-1, -1, -1].max()) / 255
    over = np.max(np.where(d_new > 1e-6, (out - cap) / np.maximum(d_new, 1e-6), 0), axis=-1, keepdims=True)
    out = y + d_new * np.where(k > 0, 1 - np.clip(over, 0, 1), 1)
    return np.clip(np.rint(out * 255), 0, 255).astype(np.uint8)


def mid_pull(cube, mid):
    """Darkens the upper mids of a look that renders bright: up to about `mid` (0..255) around 3/4 of its
    range, nothing at its black and white."""
    c = cube.astype(np.float32)
    blk, wht = c[0, 0, 0], c[-1, -1, -1]
    u = np.clip((c - blk) / np.maximum(wht - blk, 1), 0, 1)
    out = c - mid * u ** 3 * (1 - u) / (0.75 ** 3 * 0.25)
    return np.clip(np.rint(out), 0, 255).astype(np.uint8)


def mute(cube, warm=1.0, blue=1.0, leaf=1.0):
    """Less (or more) saturation by the hue of the INPUT colour, luma kept: warm (skin, orange, yellow), leaf (greens),
    blue. Factors from measurements against real scans (0.53)."""
    n = cube.shape[0]
    c = cube.astype(np.float64) / 255
    g = np.linspace(0, 1, n)
    b_, g_, r_ = np.meshgrid(g, g, g, indexing="ij")
    mx, mn = np.maximum(np.maximum(r_, g_), b_), np.minimum(np.minimum(r_, g_), b_)
    d = np.maximum(mx - mn, 1e-9)
    h = np.where(mx == r_, ((g_ - b_) / d) % 6, np.where(mx == g_, (b_ - r_) / d + 2, (r_ - g_) / d + 4)) * 60

    def band(a0, a1, b0, b1):
        x = (h - a0) % 360
        w1, w2, w3 = (a1 - a0) % 360, (b0 - a0) % 360, (b1 - a0) % 360
        return np.where(x < w1, x / w1, np.where(x < w2, 1, np.where(x < w3, 1 - (x - w2) / (w3 - w2), 0)))

    def ss(x, a, z):
        u = np.clip((x - a) / (z - a), 0, 1)
        return u * u * (3 - 2 * u)

    cx, cn = c.max(-1), c.min(-1)
    s = np.where(cx > 0, (cx - cn) / np.maximum(cx, 1e-9), 0)
    guard = (1 - ss(s, 0.42, 0.75)) * (1 - ss(cx, 0.80, 0.97))   # gains stop at strong or very bright colours
    k = 1
    for f, w in ((warm, band(340, 5, 62, 80)), (leaf, band(65, 90, 150, 175)), (blue, band(175, 200, 260, 290))):
        k = k + (f - 1) * w * (guard if f > 1 else 1)
    y = (c @ np.array([0.299, 0.587, 0.114]))[..., None]
    return np.clip(np.rint((y + (c - y) * k[..., None]) * 255), 0, 255).astype(np.uint8)


def scan800t(cube):
    """CineStill 800T (0.52): measured against 64 real scans and checked on 584 photos not used for tuning. On the
    tweaked cube: warm colours less saturated and no longer pushed to red, greens to teal, light blues to cyan, cooler
    shadows and mids, white up to ~245 (the bake caps it at 225)."""
    n = cube.shape[0]
    c = cube.astype(np.float64) / 255
    g = np.linspace(0, 1, n)
    b_, g_, r_ = np.meshgrid(g, g, g, indexing="ij")
    src = np.stack([r_, g_, b_], -1)
    w = np.array([0.299, 0.587, 0.114])

    def hsv(a):
        mx, mn = a.max(-1), a.min(-1)
        d = np.maximum(mx - mn, 1e-9)
        r, gg, bb = a[..., 0], a[..., 1], a[..., 2]
        h = np.where(mx == r, ((gg - bb) / d) % 6, np.where(mx == gg, (bb - r) / d + 2, (r - gg) / d + 4)) * 60
        return h, np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-9), 0), mx

    def rgb(h, s, v):
        h = (h % 360) / 60
        i = np.floor(h)
        f = h - i
        p, q, t = v * (1 - s), v * (1 - s * f), v * (1 - s * (1 - f))
        i = i.astype(int) % 6
        return np.stack([np.choose(i, [v, q, p, p, t, v]), np.choose(i, [t, v, v, q, p, p]), np.choose(i, [p, p, t, v, v, q])], -1)

    def ss(x, a, z):
        t = np.clip((x - a) / (z - a), 0, 1)
        return t * t * (3 - 2 * t)

    def band(h, a0, a1, b0, b1):                     # 1 between a1 and b0, fading to 0 at a0 and b1 (may cross 0)
        x = (h - a0) % 360
        w1, w2, w3 = (a1 - a0) % 360, (b0 - a0) % 360, (b1 - a0) % 360
        return np.where(x < w1, x / w1, np.where(x < w2, 1, np.where(x < w3, 1 - (x - w2) / (w3 - w2), 0)))

    hs, ssat, vs = hsv(src)
    h, s, v = hsv(c)
    y0 = c @ w
    chroma = np.clip(ssat * 4, 0, 1)                 # near-greys are left to the neutral curve
    warm = band(hs, 345, 8, 48, 70) * chroma
    lit = ss(v, .33, .55) * band(hs, 12, 22, 46, 60)  # lit brick, wood: real scans keep more (0.50 against 0.44)
    s = s - (.42 - .12 * lit) * np.maximum(s - .26, 0) * warm * ss(v, .2, .5) * (1 - .45 * ss(v, .8, 1))
    dh = ((hs - h + 180) % 360) - 180
    h = h + np.clip(dh + 1, -10, 10) * band(hs, 6, 14, 48, 62) * chroma
    green = band(hs, 62, 95, 150, 178) * chroma
    h = h + 38 * green * band(hs, 62, 100, 130, 178)
    s, v = s * (1 - .10 * green), v * (1 - .05 * green)
    blue = band(hs, 195, 208, 232, 248) * chroma
    h = h - 7 * blue * ss(v, .45, .8)
    purple = band(hs, 205, 222, 300, 325) * chroma   # deep blue, purple: nearer the input's saturation
    s = np.where(s > ssat, s - .55 * (s - ssat) * purple, s)
    out = rgb(h, np.clip(s, 0, 1), np.clip(v, 0, 1))
    out = out + ((y0 - out @ w) * np.clip(warm + blue + purple, 0, 1))[..., None]
    y = np.clip(out @ w, 0, 1)
    yy = [0, .06, .2, .4, .6, .8, .92, 1]
    off = ([0, 0, -3, -6, -5, -2, 0, 1], [0, 0, 0, 0, 0, 0, -1, -1], [2, 3, 5, 4, 3, 1, 1, 1])
    out = out + np.stack([np.interp(y, yy, o) for o in off], -1) / 255
    out = out * (1 + .095 * ss(out @ w, .62, .88))[..., None]
    # Sun, lampshades, fire: bright warm inputs keep their own hue and brightness (the bake's capped red left them a dull
    # yellow-green).
    h2, s2, v2 = hsv(np.clip(out, 0, 1))
    glow = rgb(hs - 2, np.maximum(s2, .60 * ssat), np.maximum(v2, .96 * vs))
    k = .85 * band(hs, 8, 20, 50, 66) * ss(vs, .74, .97) * ss(ssat, .2, .45)
    out = out + (glow - out) * k[..., None]
    return np.clip(np.rint(out * 255), 0, 255).astype(np.uint8)


def floor(cube, floor_r=0.0, floor_g=0.0, floor_b=0.0, floor_p=4.0):
    """Lifts the look's black by floor_* per channel, fading out towards its white ((white - x) / (white - black))
    ** floor_p: a scan's floor rather than pure black, mids barely touched. Applied last, on the finished cube (0.59,
    CineStill 800T: a slightly lifted blue-green floor, as real scans keep in deep shadows; outside review)."""
    c = cube.astype(np.float32)
    blk, wht = c[0, 0, 0], c[-1, -1, -1]
    f = np.clip((wht - c) / np.maximum(wht - blk, 1), 0, 1) ** floor_p
    out = c + np.array([floor_r, floor_g, floor_b], np.float32) * f
    return np.clip(np.rint(out), 0, 255).astype(np.uint8)


def warm_tones(cube, skin_shift=0.0, warm_sat=1.0, blue_sat=1.0, green_sat=1.0):
    """Hue and chroma on the chroma plane (YCbCr, luma kept): rotates red-magenta to orange hues (skin, shaded
    skin) skin_shift degrees towards yellow, then scales orange to yellow chroma by warm_sat and blue chroma (sky,
    ~300-30 deg) by blue_sat. Near-greys untouched; green_sat scales
    foliage greens (~188-235 deg, yellow-green to green), e.g. Portra's muted greens. Applied near the end, on the finished cube (0.59, Kodak Gold 200: shaded skin went
    red-magenta, warm tones a little flat; outside review)."""
    c = cube.astype(np.float32) / 255
    y = c @ np.array([0.299, 0.587, 0.114], np.float32)
    cb = (c[..., 2] - y) * 0.564
    cr = (c[..., 0] - y) * 0.713
    ang = np.degrees(np.arctan2(cr, cb)) % 360         # red ~109, orange/skin ~125-140, yellow ~171, magenta ~52
    chroma = np.hypot(cb, cr)
    near = np.clip(chroma * 10, 0, 1)                  # leave near-greys alone

    def band(lo, top0, top1, hi):
        return np.clip((ang - lo) / (top0 - lo), 0, 1) * np.clip((hi - ang) / (hi - top1), 0, 1)

    a = np.radians(ang + skin_shift * band(45, 85, 135, 165) * near)
    k = 1 + (warm_sat - 1) * band(110, 130, 170, 190) * near
    blue = np.clip(1 - (np.abs((ang - 345 + 180) % 360 - 180) - 25) / 30, 0, 1)   # full within 25 deg of 345, none past 55
    k = k * (1 + (blue_sat - 1) * blue * near)
    k = k * (1 + (green_sat - 1) * band(172, 188, 235, 255) * near)   # yellow-green foliage to green
    cb, cr = chroma * k * np.cos(a), chroma * k * np.sin(a)
    out = np.stack([y + cr / 0.713, y - (0.299 * (cr / 0.713) + 0.114 * (cb / 0.564)) / 0.587, y + cb / 0.564], -1)
    return np.clip(np.rint(out * 255), 0, 255).astype(np.uint8)


_M = np.array([[0.4124, 0.3576, 0.1805], [0.2126, 0.7152, 0.0722], [0.0193, 0.1192, 0.9505]])
_WP = np.array([0.95047, 1.0, 1.08883])


def _to_lab(c):
    c = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    xyz = c @ _M.T / _WP
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16 / 116)
    return np.stack([116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])], -1)


def _from_lab(lab):
    fy = (lab[..., 0] + 16) / 116
    f = np.stack([fy + lab[..., 1] / 500, fy, fy - lab[..., 2] / 200], -1)
    xyz = np.where(f ** 3 > 0.008856, f ** 3, (f - 16 / 116) / 7.787) * _WP
    c = xyz @ np.linalg.inv(_M).T
    return np.where(c <= 0.0031308, 12.92 * c, 1.055 * np.clip(c, 0, None) ** (1 / 2.4) - 0.055)


def green_hue(cube, green_hue=0.0):
    """Rotates foliage greens (Lab hue ~105-165) by green_hue degrees, + towards cyan; yellows (~95 and below) and
    near-greys stay. In Lab, where yellow-green foliage and yellow separate (on the YCbCr wheel they sit side by side).
    0.59, Fujicolor C200: foliage at Lab hue 114 like the Kodak looks; real C200 scans 132, Kodak scans 100-115."""
    c = cube.astype(np.float64) / 255
    lab = _to_lab(c)
    C = np.hypot(lab[..., 1], lab[..., 2]); H = np.degrees(np.arctan2(lab[..., 2], lab[..., 1])) % 360
    w = np.clip((H - 98) / 14, 0, 1) * np.clip((185 - H) / 20, 0, 1) * np.clip(C / 10, 0, 1)
    Hn = np.radians(H + green_hue * w)
    out = _from_lab(np.stack([lab[..., 0], C * np.cos(Hn), C * np.sin(Hn)], -1))
    changed = w > 0
    res = np.where(changed[..., None], np.clip(np.rint(out * 255), 0, 255), cube)
    return res.astype(np.uint8)


def balance(cube, bal_r=0.0, bal_g=0.0, bal_b=0.0):
    """A colour balance in the mids: adds bal_* levels at mid grey, fading to nothing at the look's own black and white
    (4x(1-x) of each channel's position between them), so blacks and whites keep their colour. 0.59, Fujicolor C200:
    cooler overall than the Kodak looks (outside review)."""
    c = cube.astype(np.float32)
    blk, wht = c[0, 0, 0], c[-1, -1, -1]
    x = np.clip((c - blk) / np.maximum(wht - blk, 1), 0, 1)
    out = c + np.array([bal_r, bal_g, bal_b], np.float32) * 4 * x * (1 - x)
    return np.clip(np.rint(out), 0, 255).astype(np.uint8)


def shoulder(cube, shoulder=0.0, shoulder_from=0.55):
    """A longer highlight roll-off: lowers the tones above shoulder_from (of the look's own white, per channel) by up to
    shoulder * (white - x), easing in with a smoothstep, so white stays white and the top of the range is spread out
    instead of crowded against it. Mids barely move (0.59, Tri-X: sunlit fur at 206-238 in the camera's JPEG was
    squeezed into 232-250; outside review: "clipped highlights ... the main thing that reads as digital")."""
    c = cube.astype(np.float32)
    wht = c[-1, -1, -1]
    x = c / np.maximum(wht, 1)
    t = np.clip((x - shoulder_from) / (1 - shoulder_from), 0, 1)
    out = (x - shoulder * (1 - x) * t * t * (3 - 2 * t)) * wht
    return np.clip(np.rint(out), 0, 255).astype(np.uint8)


TOE_KEYS = ("toe_r", "toe_g", "toe_b", "toe_p")
FLOOR_KEYS = ("floor_r", "floor_g", "floor_b", "floor_p")


def apply_tweaks(cube, t):
    tk = {k: v for k, v in t.items() if k in TOE_KEYS}
    hs = {k: v for k, v in t.items() if k in ("yellow", "green")}
    if "keep_hue" in t:
        cube = keep_hue(cube, t["keep_hue"])
    if hs:
        cube = hue_sat_tweak(cube, **hs)
    if tk:
        cube = toe(cube, **tk)
    if "mid" in t:
        cube = mid_pull(cube, t["mid"])
    if t.get("scan800t"):
        cube = scan800t(cube)
    mu = {k: v for k, v in t.items() if k in ("warm", "blue", "leaf")}
    if mu:
        cube = mute(cube, **mu)
    wt = {k: v for k, v in t.items() if k in ("skin_shift", "warm_sat", "blue_sat", "green_sat")}
    if wt:
        cube = warm_tones(cube, **wt)
    if "green_hue" in t:
        cube = green_hue(cube, t["green_hue"])
    bk = {k: v for k, v in t.items() if k in ("bal_r", "bal_g", "bal_b")}
    if bk:
        cube = balance(cube, **bk)
    sk = {k: v for k, v in t.items() if k in ("shoulder", "shoulder_from")}
    if sk:
        cube = shoulder(cube, **sk)
    if "shoulder2" in t:   # a second, steeper roll-off in the very top (Tri-X: the top of its curve stayed nearly flat)
        cube = shoulder(cube, t["shoulder2"], t.get("shoulder2_from", 0.88))
    fk = {k: v for k, v in t.items() if k in FLOOR_KEYS}
    if fk:
        cube = floor(cube, **fk)
    return cube


def main(luts, dest):
    luts, dest = Path(luts).resolve(), Path(dest)
    sources = [(table(luts / "looks.txt"), luts / "out", "rt"), (table(luts / "emulsion-trial.txt"), luts / "out-emulsion", "ep"),
               (table(luts / "candidates-rt.txt"), luts / "out-cand", "rt"),
               (table(luts / "original.txt"), luts / "out-original", "own")]   # our own looks (MIT, like the app)
    order, grain, strength, halation, group, sharp = [], {}, {}, {}, {}, {}
    cur_group = ""
    for l in (luts / "app-looks.txt").read_text(encoding="utf-8").splitlines():
        if l.startswith("## "):
            full, _, short = l[3:].partition("|")
            full = full.strip().replace(";", ",")
            cur_group = full + ";" + (short.strip() or full.split()[0])   # "Full name;Short" in looks.txt
        elif l.strip() and not l.lstrip().startswith("#"):
            group[l.split()[0]] = cur_group
            parts = l.split()
            order.append(parts[0])
            grain[parts[0]] = max(0, min(100, int(parts[1]))) if len(parts) > 1 else 0
            strength[parts[0]] = max(0, min(100, int(parts[2]))) if len(parts) > 2 else 100
            halation[parts[0]] = max(0, min(100, int(parts[3]))) if len(parts) > 3 else 0
            sharp[parts[0]] = max(-3, min(3, int(parts[4]))) if len(parts) > 4 else 0
    tweaks = read_tweaks(luts / "tweaks.txt")
    if dest.exists():
        for f in dest.glob("*"):
            f.unlink()
    dest.mkdir(parents=True, exist_ok=True)
    lines, used, tags, ep_names = [], set(), set(), []
    for lid in order:
        for tab, folder, kind in sources:
            if lid in tab:
                tag, name = tab[lid]
                break
        else:
            raise SystemExit(f"unknown look id: {lid}")
        if tag in tags:
            raise SystemExit(f"duplicate tag {tag}")
        tags.add(tag)
        raw = (folder / f"{lid}.lut").read_bytes()
        s = strength[lid] / 100
        if s < 1:
            # Strength: blend the LUT part-way back to identity (keeps the look's character, less of it).
            n = raw[4]
            cube = np.frombuffer(raw[5:], np.uint8).reshape(n, n, n, 3).astype(np.float32)
            g = np.linspace(0, 255, n, dtype=np.float32)
            b_, g_, r_ = np.meshgrid(g, g, g, indexing="ij")
            ident = np.stack([r_, g_, b_], -1)
            cube = np.clip(np.rint(ident + s * (cube - ident)), 0, 255).astype(np.uint8)
            raw = raw[:5] + cube.tobytes()
        if lid in tweaks:
            n = raw[4]
            cube = np.frombuffer(raw[5:], np.uint8).reshape(n, n, n, 3)
            raw = raw[:5] + apply_tweaks(cube, tweaks[lid]).tobytes()
        (dest / f"{lid}.lut").write_bytes(raw)
        # looks.txt: id|TAG|Name|grain|halation|group|sharpness (camera sharpness -3..3 while shooting)
        lines.append(f"{lid}|{tag}|{name}|{grain[lid]}|{halation[lid]}|{group[lid]}|{sharp[lid]}")
        used.add(kind)
        if kind == "ep":
            ep_names.append(name)
        elif lid == "almostdark":          # derived from an EmulsionPresets look: keep its notice
            ep_names.append(name + " (modified from Vision3 500T)")
    (dest / "looks.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    lic = []
    if "rt" in used:
        lic.append((luts / "out" / "LICENSE.txt").read_text(encoding="utf-8").strip())
    if "ep" in used:
        mit = (luts.parent / "vendor" / "EmulsionPresets" / "LICENSE").read_text(encoding="utf-8").strip()
        lic.append(
            ", ".join(ep_names) + "\n"
            "are approximations baked from Lightroom presets in EmulsionPresets by peva3\n"
            "(https://github.com/peva3/EmulsionPresets), used under the MIT License:\n\n" + mit)
    if "own" in used:
        own = [n for l, n in zip(lines, [x.split("|")[2] for x in lines]) if l.split("|")[0] in table(luts / "original.txt")]
        lic.append(", ".join(own) + " " + ("was" if len(own) == 1 else "were") +
                   " made for Film Drawer and " + ("is" if len(own) == 1 else "are") +
                   " covered by the app's MIT licence (see LICENSE.txt).")
    (dest / "LICENSE.txt").write_text("\n\n".join(lic) + "\n", encoding="utf-8")
    print(f"{len(lines)} looks -> {dest}")


if __name__ == "__main__":
    main(*sys.argv[1:3])
