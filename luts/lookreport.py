"""Objective look report: runs every app look over the 24 ColorChecker colours (sRGB) and reports
grey balance, skin and sky rendering, and tone, in CIELAB. Also draws a patch chart.

Usage: python -I lookreport.py <assets_luts_dir> <out_dir>
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).parent))
from xmp2lut import tetra  # noqa: E402  (same tetrahedral maths as the camera)

# ColorChecker 24, sRGB (BabelColor averages, D65)
CC = [("dark skin", (115, 82, 68)), ("light skin", (194, 150, 130)), ("blue sky", (98, 122, 157)),
      ("foliage", (87, 108, 67)), ("blue flower", (133, 128, 177)), ("bluish green", (103, 189, 170)),
      ("orange", (214, 126, 44)), ("purplish blue", (80, 91, 166)), ("moderate red", (193, 90, 99)),
      ("purple", (94, 60, 108)), ("yellow green", (157, 188, 64)), ("orange yellow", (224, 163, 46)),
      ("blue", (56, 61, 150)), ("green", (70, 148, 73)), ("red", (175, 54, 60)),
      ("yellow", (231, 199, 31)), ("magenta", (187, 86, 149)), ("cyan", (8, 133, 161)),
      ("white", (243, 243, 242)), ("neutral 8", (200, 200, 200)), ("neutral 6.5", (160, 160, 160)),
      ("neutral 5", (122, 122, 121)), ("neutral 3.5", (85, 85, 85)), ("black", (52, 52, 52))]


def lab(rgb):
    c = np.asarray(rgb, float) / 255
    c = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    M = np.array([[0.4124, 0.3576, 0.1805], [0.2126, 0.7152, 0.0722], [0.0193, 0.1192, 0.9505]])
    xyz = c @ M.T / np.array([0.95047, 1.0, 1.08883])
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16 / 116)
    L = 116 * f[..., 1] - 16
    a = 500 * (f[..., 0] - f[..., 1])
    b = 200 * (f[..., 1] - f[..., 2])
    return np.stack([L, a, b], -1)


def hue(l):
    return np.degrees(np.arctan2(l[..., 2], l[..., 1])) % 360


def chroma(l):
    return np.hypot(l[..., 1], l[..., 2])


def cast(a, b):
    if np.hypot(a, b) < 2.5:
        return "neutral"
    h = np.degrees(np.arctan2(b, a)) % 360
    names = [(30, "magenta-red"), (75, "warm/orange"), (110, "yellow"), (165, "yellow-green"),
             (220, "green"), (275, "cyan/teal"), (320, "blue"), (360, "magenta")]
    return next(n for lim, n in names if h < lim) + f" ({np.hypot(a, b):.0f})"


def main(assets, out):
    assets, out = Path(assets), Path(out)
    out.mkdir(parents=True, exist_ok=True)
    looks = [l.split("|") for l in (assets / "looks.txt").read_text(encoding="utf-8").splitlines() if l.strip()]
    src = np.array([c for _, c in CC], np.float32)[None, :, :]          # 1 x 24 x 3
    L0 = lab(src[0])
    rows, report = [("Original", src[0].astype(np.uint8))], []
    hdr = f"{'look':18s} {'grey cast (mid)':22s} {'black L':>7s} {'white L':>7s} {'contrast':>8s}  " \
          f"{'skin hue':>9s} {'skin sat':>8s}  {'sky hue':>8s} {'sky sat':>7s}  {'mean sat':>8s}  flags"
    report.append(hdr)
    report.append("-" * len(hdr))
    for lid, tag, name, *_ in looks:
        raw = (assets / f"{lid}.lut").read_bytes()
        n = raw[4]
        cube = np.frombuffer(raw[5:], np.uint8).reshape(n, n, n, 3).astype(np.float32)
        o = tetra(cube, src)[0]
        L1 = lab(o)
        rows.append((name, o))
        bw = chroma(L1).mean() < 3
        mid = L1[21]                                                     # neutral 5
        contrast = (L1[20, 0] - L1[22, 0]) / (L0[20, 0] - L0[22, 0])     # slope 6.5 vs 3.5
        flags = []
        if not bw:
            sh = ((hue(L1[[0, 1]]) - hue(L0[[0, 1]]) + 180) % 360 - 180).mean()
            ss = (chroma(L1[[0, 1]]) / chroma(L0[[0, 1]])).mean()
            kh = (hue(L1[2]) - hue(L0[2]) + 180) % 360 - 180
            ks = chroma(L1[2]) / chroma(L0[2])
            msat = (chroma(L1[:18]) / chroma(L0[:18])).mean()
            skin_h = hue(L1[1])
            if skin_h < 30: flags.append("skin->magenta")
            if skin_h > 75: flags.append("skin->yellow/green")
            if abs(sh) > 12: flags.append(f"skin hue {sh:+.0f}")
            if abs(kh) > 20: flags.append(f"sky hue {kh:+.0f}")
            if np.hypot(mid[1], mid[2]) > 8: flags.append("strong grey cast")
            sk = f"{sh:+8.0f}° {ss:7.2f}x  {kh:+7.0f}° {ks:6.2f}x  {msat:7.2f}x"
        else:
            sk = f"{'(B&W)':>9s} {'':8s}  {'':8s} {'':7s}  {'':8s}"
        if L1[23, 0] > 30: flags.append("faded blacks")
        if L1[18, 0] > 99 and L1[19, 0] > 92: flags.append("bright/clipping whites")
        if contrast > 1.35: flags.append("high contrast")
        if contrast < 0.75: flags.append("flat")
        report.append(f"{name:18s} {cast(mid[1], mid[2]):22s} {L1[23, 0]:7.1f} {L1[18, 0]:7.1f} {contrast:8.2f}  {sk}  "
                      + ", ".join(flags))
    (out / "look-report.txt").write_text("\n".join(report) + "\n", encoding="utf-8")
    print("\n".join(report))

    # Chart: one row per look, 24 patches.
    pw, lab_w = 34, 150
    img = Image.new("RGB", (lab_w + 24 * pw + 10, len(rows) * (pw + 4) + 10), (30, 30, 30))
    d = ImageDraw.Draw(img)
    for r, (name, pats) in enumerate(rows):
        y = 5 + r * (pw + 4)
        d.text((6, y + pw // 2 - 6), name, fill=(230, 230, 230))
        for i, c in enumerate(pats):
            d.rectangle([lab_w + i * pw, y, lab_w + (i + 1) * pw - 2, y + pw], fill=tuple(int(v) for v in c))
    img.save(out / "look-chart.png")
    print("wrote", out / "look-report.txt", "and", out / "look-chart.png")


if __name__ == "__main__":
    main(*sys.argv[1:3])
