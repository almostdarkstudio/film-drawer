"""looksheet.py <photo.jpg> <out.jpg> <lutdir>:<id>[:label] ... : one photo rendered through many LUTs
(same tetrahedral maths as the camera), as a labelled contact sheet. Applies EXIF orientation."""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageOps

sys.path.insert(0, str(Path(__file__).parent))
from xmp2lut import tetra  # noqa: E402

photo, out = sys.argv[1], sys.argv[2]
im = ImageOps.exif_transpose(Image.open(photo)).convert("RGB")
im.thumbnail((360, 360), Image.LANCZOS)
base = np.asarray(im, np.float32)
tiles = [("Original", np.asarray(im))]
for spec in sys.argv[3:]:
    parts = spec.split(":")
    d, lid = parts[0] + (":" + parts[1] if len(parts[0]) == 1 else ""), parts[-2] if len(parts[0]) == 1 else parts[1]
    # Windows drive letters contain ':'; accept "C:\dir:id:label" and "dir:id:label".
    if len(parts[0]) == 1:
        d, lid, label = parts[0] + ":" + parts[1], parts[2], parts[3] if len(parts) > 3 else parts[2]
    else:
        d, lid, label = parts[0], parts[1], parts[2] if len(parts) > 2 else parts[1]
    raw = (Path(d) / f"{lid}.lut").read_bytes()
    n = raw[4]
    cube = np.frombuffer(raw[5:], np.uint8).reshape(n, n, n, 3).astype(np.float32)
    tiles.append((label, tetra(cube, base)))
cols = 6
tw, th = tiles[0][1].shape[1], tiles[0][1].shape[0]
rows = -(-len(tiles) // cols)
sheet = Image.new("RGB", (cols * (tw + 8) + 8, rows * (th + 26) + 8), (28, 28, 28))
dr = ImageDraw.Draw(sheet)
for i, (label, arr) in enumerate(tiles):
    x, y = 8 + (i % cols) * (tw + 8), 8 + (i // cols) * (th + 26)
    dr.text((x, y + 3), label, fill=(235, 235, 235))
    sheet.paste(Image.fromarray(arr), (x, y + 20))
sheet.save(out, quality=88)
print("wrote", out)
