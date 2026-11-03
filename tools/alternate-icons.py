# Adaptive launcher icons for the iOS alternate icons (P icon, Classic): the iOS 1024 artwork fills
# the 72 dp viewport of a 108 dp layer; the outer 18 dp repeat the edge colours. Classic's PNG has
# white rounded corners baked in, which are filled from the surrounding gradient first. The themed
# (monochrome) layer is the artwork's silhouette.
#
#   python3 -m venv /tmp/v && /tmp/v/bin/pip install numpy scipy pillow
#   /tmp/v/bin/python tools/alternate-icons.py app/src/main/res
#
# Reads the iOS app's asset catalog from ../prl-hub (PRL_HUB to override).
import sys, colorsys
import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage

import os
A = os.path.join(os.environ.get("PRL_HUB", os.path.join(os.path.dirname(__file__), "..", "..", "prl-hub")), "Sources", "Assets.xcassets")
OUT = sys.argv[1]
DENS = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

def load(name):
    return np.asarray(Image.open(f"{A}/{name}.appiconset/icon_1024_ios.png").convert("RGB")).astype(np.float32)

def fill_corners(img):
    # Near-white pixels connected to a corner are the baked-in rounding: fill them by diffusion.
    white = (img.min(axis=2) > 235)
    lab, _ = ndimage.label(white)
    h, w = white.shape
    corner = set(lab[y, x] for y, x in [(0, 0), (0, w - 1), (h - 1, 0), (h - 1, w - 1)]) - {0}
    hole = np.isin(lab, list(corner))
    hole = ndimage.binary_dilation(hole, iterations=6)   # anti-aliased rim
    out = img.copy()
    known = ~hole
    # Nearest known pixel, then smooth inside the hole only.
    idx = ndimage.distance_transform_edt(~known, return_distances=False, return_indices=True)
    out = out[idx[0], idx[1]]
    for _ in range(40):
        blurred = np.stack([ndimage.gaussian_filter(out[..., c], 6) for c in range(3)], axis=2)
        out[hole] = blurred[hole]
    return out

def padded(img):
    # 1024 → viewport; pad each side by 18/72 of the size with edge colours.
    pad = int(round(img.shape[0] * 18 / 72))
    return np.pad(img, ((pad, pad), (pad, pad), (0, 0)), mode="edge")

def save_layers(name, full):
    im = Image.fromarray(np.clip(full, 0, 255).astype(np.uint8), "RGB")
    for d, px in DENS.items():
        im.resize((px, px), Image.LANCZOS).save(f"{OUT}/mipmap-{d}/{name}.png", optimize=True)

def mono(name, mask, fill=False):
    # mask: bool, motif pixels of the 1024 art → white on transparent, at the viewport scale.
    m = ndimage.binary_opening(mask, iterations=2)
    if fill: m = ndimage.binary_fill_holes(m)
    m = ndimage.binary_opening(m, iterations=3)
    alpha = Image.fromarray((m * 255).astype(np.uint8), "L").filter(ImageFilter.GaussianBlur(1.2))
    pad = int(round(1024 * 18 / 72))
    canvas = Image.new("L", (1024 + 2 * pad, 1024 + 2 * pad), 0)
    canvas.paste(alpha, (pad, pad))
    rgba = Image.merge("RGBA", [Image.new("L", canvas.size, 255)] * 3 + [canvas])
    for d, px in DENS.items():
        rgba.resize((px, px), Image.LANCZOS).save(f"{OUT}/mipmap-{d}/{name}.png", optimize=True)

import os
for d in DENS: os.makedirs(f"{OUT}/mipmap-{d}", exist_ok=True)

modern = load("AppIconModern")
save_layers("ic_launcher_modern_background", padded(modern))
mono("ic_launcher_modern_monochrome", modern.max(axis=2) > 60)

classic = fill_corners(load("AppIconClassic"))
save_layers("ic_launcher_classic_background", padded(classic))
hsv = np.asarray(Image.fromarray(classic.astype(np.uint8)).convert("HSV")).astype(np.float32)
hue = hsv[..., 0] * 360 / 255; sat = hsv[..., 1] / 255
bg = (hue > 165) & (hue < 250) & (sat > 0.45)
lum = classic.mean(axis=2)
shape = ndimage.binary_fill_holes(ndimage.binary_opening(~bg, iterations=2))
mono("ic_launcher_classic_monochrome", shape & (lum > 70))
