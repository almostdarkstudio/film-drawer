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


TOE_KEYS = ("toe_r", "toe_g", "toe_b", "toe_p")


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
