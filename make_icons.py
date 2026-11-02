import os
from PIL import Image, ImageDraw

SRC = r"C:\Users\HP\.dsh\attachments\v1\objects\5a\5a1a46fadb70e132ef1632246712ed2ae0dd6cb37e52c93962a29e291b01052f"
RES = r"C:\Users\HP\Downloads\cartify\app\src\main\res"

logo = Image.open(SRC).convert("RGB")
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
FOREGROUND = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
SAFE = 66.0 / 108.0

def rounded(img, radius_ratio=0.235):
    w, h = img.size
    scale = 4
    big = img.resize((w * scale, h * scale), Image.LANCZOS)
    mask = Image.new("L", (w * scale, h * scale), 0)
    d = ImageDraw.Draw(mask)
    r = int(min(big.size) * radius_ratio)
    d.rounded_rectangle([0, 0, big.size[0] - 1, big.size[1] - 1], radius=r, fill=255)
    big.putalpha(mask)
    return big.resize((w, h), Image.LANCZOS)

for dpi, size in LEGACY.items():
    out = rounded(logo.resize((size, size), Image.LANCZOS))
    folder = os.path.join(RES, "mipmap-" + dpi)
    out.save(os.path.join(folder, "ic_launcher.png"), "PNG")
    out.save(os.path.join(folder, "ic_launcher_round.png"), "PNG")
    print("legacy", dpi, size)

for dpi, size in FOREGROUND.items():
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = int(round(size * SAFE))
    art = rounded(logo.resize((inner, inner), Image.LANCZOS), 0.18)
    off = (size - inner) // 2
    canvas.paste(art, (off, off), art)
    folder = os.path.join(RES, "mipmap-" + dpi)
    canvas.save(os.path.join(folder, "ic_launcher_foreground.png"), "PNG")
    print("foreground", dpi, size, "art", inner)

print("done")
