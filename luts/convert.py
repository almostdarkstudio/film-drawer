"""Convert level-12 HaldCLUT PNGs into compact 33^3 LUTs for the camera app.

Usage: python -I convert.py <HaldCLUT.zip> <looks.txt> <out_dir>

Output per look: <id>.lut =
    4 bytes  b"FLUT"
    1 byte   cube size N (33)
    N^3 * 3 bytes RGB (uint8), red index fastest, then green, then blue.
Also writes looks.txt (id|name), LICENSE.txt, and a preview sheet + error report.
"""
import io
import sys
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

N = 33  # output cube size


def read_looks(path):
    looks = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        lid, tag, name, src = [p.strip() for p in line.split("|")]
        if len(tag) != 3 or not tag.isalnum() or tag != tag.upper():
            raise ValueError(f"{lid}: tag must be 3 upper-case letters/digits (8.3 file names)")
        looks.append((lid, tag, name, src))
    tags = [t for _, t, _, _ in looks]
    if len(set(tags)) != len(tags):
        raise ValueError("duplicate tags")
    return looks


def hald_to_cube(img):
    a = np.asarray(img.convert("RGB"), dtype=np.float32)
    total = a.shape[0] * a.shape[1]
    size = round(total ** (1 / 3))
    if size ** 3 != total:
        raise ValueError(f"not a Hald CLUT: {a.shape}")
    # Hald pixel order: red fastest, then green, then blue -> cube[b, g, r]
    return a.reshape(size, size, size, 3), size


def sample_trilinear(cube, rgb01):
    """Sample cube[b,g,r] at rgb in [0,1]; rgb01 shape (..., 3). Returns float 0..255."""
    s = cube.shape[0] - 1
    p = np.clip(rgb01, 0, 1) * s
    i0 = np.minimum(np.floor(p).astype(np.int32), s - 1)
    f = p - i0
    r0, g0, b0 = i0[..., 0], i0[..., 1], i0[..., 2]
    fr, fg, fb = f[..., 0:1], f[..., 1:2], f[..., 2:3]
    out = 0
    for db, wb in ((0, 1 - fb), (1, fb)):
        for dg, wg in ((0, 1 - fg), (1, fg)):
            for dr, wr in ((0, 1 - fr), (1, fr)):
                out = out + cube[b0 + db, g0 + dg, r0 + dr] * (wr * wg * wb)
    return out


def shrink(cube):
    g = np.linspace(0, 1, N, dtype=np.float32)
    b, gg, r = np.meshgrid(g, g, g, indexing="ij")
    rgb = np.stack([r, gg, b], axis=-1)  # indexed [b,g,r]
    return np.clip(np.rint(sample_trilinear(cube, rgb)), 0, 255).astype(np.uint8)


def test_image(w=480, h=320):
    """Synthetic chart: hue sweep x brightness, plus a grey ramp and skin-ish patches."""
    x = np.linspace(0, 1, w, dtype=np.float32)
    y = np.linspace(0, 1, h, dtype=np.float32)[:, None]
    hue = x[None, :] * 6
    c = np.stack([np.clip(np.abs(hue - 3) - 1, 0, 1),
                  np.clip(2 - np.abs(hue - 2), 0, 1),
                  np.clip(2 - np.abs(hue - 4), 0, 1)], axis=-1)
    sat = np.clip(1 - y * 1.4, 0, 1)[..., None]
    val = np.clip(0.15 + y * 1.1, 0, 1)[..., None]
    img = (c * sat + (1 - sat)) * val
    img[int(h * 0.85):, :, :] = x[None, :, None]  # grey ramp
    skins = [(0.95, 0.80, 0.70), (0.85, 0.65, 0.50), (0.60, 0.42, 0.30), (0.35, 0.24, 0.18)]
    for i, s in enumerate(skins):
        img[int(h * 0.62):int(h * 0.82), int(w * (0.05 + i * 0.12)):int(w * (0.15 + i * 0.12))] = s
    return np.clip(img, 0, 1)


def main(zip_path, looks_path, out_dir):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    looks = read_looks(looks_path)
    chart = test_image()
    tiles, report = [("Original", (chart * 255).astype(np.uint8))], []

    with zipfile.ZipFile(zip_path) as z:
        for lid, _tag, name, src in looks:
            full, size = hald_to_cube(Image.open(io.BytesIO(z.read(src))))
            small = shrink(full)
            (out / f"{lid}.lut").write_bytes(b"FLUT" + bytes([N]) + small.tobytes())

            # Accuracy: 33^3 trilinear vs. the full 144^3 table over 100k random colours.
            rnd = np.random.default_rng(1).random((100000, 3), dtype=np.float32)
            err = np.abs(sample_trilinear(small.astype(np.float32), rnd) - sample_trilinear(full, rnd))
            report.append(f"{lid:11s} src={size}^3  mean err {err.mean():.2f}  99th pct {np.percentile(err, 99):.2f}  max {err.max():.1f} (of 255)")
            tiles.append((name, np.clip(np.rint(sample_trilinear(small.astype(np.float32), chart)), 0, 255).astype(np.uint8)))

    (out / "looks.txt").write_text("".join(f"{l}|{t}|{n}\n" for l, t, n, _ in looks), encoding="utf-8")
    (out / "LICENSE.txt").write_text(
        "Film looks derived from the RawTherapee Film Simulation Collection (2015-09-20)\n"
        "by Pat David, Pavlov Dmitry and Michael Ezra, licensed CC BY-SA 4.0\n"
        "(https://creativecommons.org/licenses/by-sa/4.0/). Resampled from 144^3 to 33^3.\n"
        "These derived LUTs are shared under the same licence. Film stock names are used\n"
        "only to identify the look each approximates; no affiliation or endorsement.\n",
        encoding="utf-8")
    (out / "report.txt").write_text("\n".join(report) + "\n", encoding="utf-8")

    # Preview sheet: 3 columns of labelled tiles.
    th, tw = tiles[0][1].shape[:2]
    cols, lab = 3, 22
    rows = -(-len(tiles) // cols)
    sheet = Image.new("RGB", (cols * (tw + 10) + 10, rows * (th + lab + 10) + 10), (30, 30, 30))
    d = ImageDraw.Draw(sheet)
    for i, (name, arr) in enumerate(tiles):
        x, y = 10 + (i % cols) * (tw + 10), 10 + (i // cols) * (th + lab + 10)
        d.text((x, y + 4), name, fill=(235, 235, 235))
        sheet.paste(Image.fromarray(arr), (x, y + lab))
    sheet.save(out / "preview.png")
    print("\n".join(report))
    print(f"\nwrote {len(looks)} LUTs ({sum((out / f'{l}.lut').stat().st_size for l, _, _, _ in looks) // 1024} KB) to {out}")


if __name__ == "__main__":
    main(*sys.argv[1:4])
