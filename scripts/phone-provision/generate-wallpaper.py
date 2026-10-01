#!/usr/bin/env python3
"""Generate CrypTAK kiosk wallpaper for HMDM managed devices."""
from PIL import Image, ImageDraw, ImageFont

W, H = 1440, 3120

img = Image.new('RGBA', (W, H))
draw = ImageDraw.Draw(img)

# Dark gradient background
for y in range(H):
    r = int(10 + (26-10) * y / H)
    g = int(10 + (26-10) * y / H)
    b = int(26 + (46-26) * y / H)
    draw.line([(0, y), (W, y)], fill=(r, g, b, 255))

# Subtle tactical grid
for y in range(0, H, 120):
    draw.line([(0, y), (W, y)], fill=(255, 255, 255, 8))
for x in range(0, W, 120):
    draw.line([(x, 0), (x, H)], fill=(255, 255, 255, 8))

# CrypTAK logo overlay (semi-transparent)
import os
logo_path = os.path.join(os.path.dirname(__file__), '..', 'logo.png')
logo = Image.open(logo_path).convert('RGBA')
logo = logo.resize((400, 400), Image.LANCZOS)
logo_data = list(logo.getdata())
new_data = [(r, g, b, int(a * 0.35)) for r, g, b, a in logo_data]
logo.putdata(new_data)
logo_x = (W - 400) // 2
logo_y = (H - 400) // 2 - 100
img.paste(logo, (logo_x, logo_y), logo)

# "CRYPTAK" text
try:
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 72)
except:
    font = ImageFont.load_default()
text = "C R Y P T A K"
bbox = draw.textbbox((0, 0), text, font=font)
tw = bbox[2] - bbox[0]
tx = (W - tw) // 2
ty = logo_y + 400 + 60
draw.text((tx, ty), text, fill=(255, 255, 255, 70), font=font)

out = os.path.join(os.path.dirname(__file__), 'cryptak-wallpaper.png')
img.save(out)
print(f"Saved {W}x{H} wallpaper to {out}")
