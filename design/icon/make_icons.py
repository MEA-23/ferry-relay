"""Renders every app icon from mark.png (white mark, transparent background).

    python3 design/icon/make_icons.py        # needs Pillow + numpy

iOS: AppIcon (Ferry teal) plus the alternates Ocean and Paper, each with dark (navy) and
tinted variants, and small previews for the in-app icon picker.
Android: adaptive-icon foreground + monochrome into design/icon/android/ (copy into res/).
"""

import json
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "ios/Ferry/Assets.xcassets"
ANDROID_OUT = Path(__file__).parent / "android"
MARK = Image.open(Path(__file__).parent / "mark.png").convert("RGBA")

N = 1024
MARK_SCALE = 0.53  # mark width as a fraction of the iOS canvas

# Background: (top-left, bottom-right) gradient stops. Mark: RGB.
TEAL = ((14, 92, 99), (27, 122, 141))
OCEAN = ((10, 110, 110), (30, 95, 170))
PAPER = ((244, 244, 238), (244, 244, 238))
NAVY = ((2, 28, 52), (2, 28, 52))

ICONS = {
    # name: (light background, light mark)
    "AppIcon": (TEAL, (240, 255, 250)),
    "AppIcon-Ocean": (OCEAN, (255, 255, 255)),
    "AppIcon-Paper": (PAPER, (27, 122, 141)),
}
DARK = (NAVY, (235, 250, 245))


def gradient(stops, size=N):
    t = (np.add.outer(np.arange(size), np.arange(size)) / (2 * size - 2))[..., None]
    c1, c2 = np.array(stops[0], float), np.array(stops[1], float)
    return Image.fromarray((c1 * (1 - t) + c2 * t).astype("uint8"), "RGB")


def place_mark(canvas, color, scale):
    w = round(canvas.width * scale)
    h = round(MARK.height * w / MARK.width)
    alpha = MARK.getchannel("A").resize((w, h), Image.LANCZOS)
    tile = Image.new("RGB", (w, h), color)
    canvas.paste(tile, ((canvas.width - w) // 2, (canvas.height - h) // 2), alpha)
    return canvas


def icon(stops, mark_color):
    return place_mark(gradient(stops), mark_color, MARK_SCALE)


def write_iconset(name, light):
    folder = ASSETS / f"{name}.appiconset"
    folder.mkdir(parents=True, exist_ok=True)
    for old in folder.glob("*.png"):
        old.unlink()
    icon(*light).save(folder / f"{name}.png")
    icon(*DARK).save(folder / f"{name}-Dark.png")
    # Tinted: opaque grayscale; iOS applies the user's tint.
    icon(((0, 0, 0), (0, 0, 0)), (255, 255, 255)).convert("L").save(folder / f"{name}-Tinted.png")

    def entry(file, appearance=None):
        e = {"filename": file, "idiom": "universal", "platform": "ios", "size": "1024x1024"}
        if appearance:
            e["appearances"] = [{"appearance": "luminosity", "value": appearance}]
        return e

    contents = {
        "images": [entry(f"{name}.png"), entry(f"{name}-Dark.png", "dark"), entry(f"{name}-Tinted.png", "tinted")],
        "info": {"author": "xcode", "version": 1},
    }
    (folder / "Contents.json").write_text(json.dumps(contents, indent=2) + "\n")


def write_preview(name, light):
    folder = ASSETS / f"{name.replace('AppIcon', 'IconPreview')}.imageset"
    folder.mkdir(parents=True, exist_ok=True)
    icon(*light).resize((180, 180), Image.LANCZOS).save(folder / "preview.png")
    contents = {"images": [{"filename": "preview.png", "idiom": "universal"}], "info": {"author": "xcode", "version": 1}}
    (folder / "Contents.json").write_text(json.dumps(contents, indent=2) + "\n")


def write_android():
    # Adaptive icon: 108dp canvas, 66dp safe zone. xxxhdpi = 4x → 432px; mark ≈ 52dp wide.
    ANDROID_OUT.mkdir(exist_ok=True)
    fg = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    w = 208
    h = round(MARK.height * w / MARK.width)
    alpha = MARK.getchannel("A").resize((w, h), Image.LANCZOS)
    fg.paste(Image.new("RGBA", (w, h), (240, 255, 250, 255)), ((432 - w) // 2, (432 - h) // 2), alpha)
    fg.save(ANDROID_OUT / "ic_launcher_art.webp", lossless=True)
    mono = fg.copy()
    mono.paste(Image.new("RGBA", (w, h), (255, 255, 255, 255)), ((432 - w) // 2, (432 - h) // 2), alpha)
    mono.save(ANDROID_OUT / "ic_launcher_monochrome.webp", lossless=True)


if __name__ == "__main__":
    for name, light in ICONS.items():
        write_iconset(name, light)
        write_preview(name, light)
    write_android()
    print("icons written")
