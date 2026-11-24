# -*- coding: utf-8 -*-
"""Generates the 2D design assets for the HabiTrain replay video export.

Run:  python gen_assets.py <output textures dir> <output font dir>
All art is procedural so it can be regenerated / tweaked deterministically.
"""
import math
import random
import shutil
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageChops

OUT = Path(sys.argv[1])
FONT_OUT = Path(sys.argv[2])
OUT.mkdir(parents=True, exist_ok=True)
FONT_OUT.mkdir(parents=True, exist_ok=True)
WIN_FONTS = Path("C:/Windows/Fonts")
BEBAS = str(WIN_FONTS / "BebasNeue-Regular.ttf")
OSWALD = str(WIN_FONTS / "Oswald-Medium.ttf")
OSWALD_BOLD = str(WIN_FONTS / "Oswald-Bold.ttf")
rng = random.Random(20261116)

NAVY = (10, 16, 34)
NIGHT2 = (17, 26, 51)
BRASS = (208, 166, 88)
BRASS_L = (244, 216, 148)
BRASS_D = (138, 106, 53)
CHAMP = (255, 244, 218)


def save(img, name):
    img.save(OUT / name, optimize=True)
    print("wrote", name, img.size)


def radial(size, inner=0.0, power=2.0):
    y, x = np.mgrid[0:size, 0:size].astype(np.float32)
    c = (size - 1) / 2
    d = np.sqrt((x - c) ** 2 + (y - c) ** 2) / c
    return np.clip(d, 0, 1), x, y


def rgba_from_alpha(alpha, color=(255, 255, 255)):
    a = (np.clip(alpha, 0, 1) * 255).astype(np.uint8)
    h, w = a.shape
    arr = np.zeros((h, w, 4), np.uint8)
    arr[..., 0], arr[..., 1], arr[..., 2] = color
    arr[..., 3] = a
    return Image.fromarray(arr, "RGBA")


def vgradient(w, h, stops):
    """stops: list of (pos 0..1, (r,g,b))"""
    ys = np.linspace(0, 1, h, dtype=np.float32)
    out = np.zeros((h, 3), np.float32)
    for i in range(3):
        out[:, i] = np.interp(ys, [s[0] for s in stops], [s[1][i] for s in stops])
    arr = np.repeat(out[:, None, :], w, axis=1)
    return Image.fromarray(arr.astype(np.uint8), "RGB")


# ----------------------------------------------------------------------------- soft primitives

def gen_glow():
    d, _, _ = radial(128)
    a = (1 - d) ** 2.4
    save(rgba_from_alpha(a), "glow.png")


def gen_ring():
    d, _, _ = radial(256)
    a = np.exp(-((d - 0.82) / 0.07) ** 2) + 0.35 * np.exp(-((d - 0.82) / 0.2) ** 2)
    save(rgba_from_alpha(np.clip(a, 0, 1)), "ring.png")


def gen_bokeh():
    d, x, y = radial(128)
    # hexagonal aperture
    c = 63.5
    ang = np.arctan2(y - c, x - c)
    hexr = np.cos(math.pi / 6) / np.cos(((ang + math.pi / 6) % (math.pi / 3)) - math.pi / 6)
    dd = d / hexr
    body = np.clip((0.92 - dd) / 0.05, 0, 1) * 0.55
    rim = np.exp(-((dd - 0.86) / 0.05) ** 2) * 0.6
    save(rgba_from_alpha(np.clip(body + rim, 0, 1)), "bokeh.png")


def gen_streak():
    w, h = 256, 32
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    u = np.abs(x - (w - 1) / 2) / ((w - 1) / 2)
    v = np.abs(y - (h - 1) / 2) / ((h - 1) / 2)
    a = (1 - u) ** 1.6 * np.exp(-(v / 0.28) ** 2)
    a += 0.6 * (1 - u) ** 6 * np.exp(-(v / 0.08) ** 2)
    save(rgba_from_alpha(np.clip(a, 0, 1)), "streak.png")


def gen_rays():
    s = 512
    d, x, y = radial(s)
    c = (s - 1) / 2
    ang = np.arctan2(y - c, x - c)
    rays = 18
    k = (ang / (2 * math.pi) * rays) % 1.0
    tri = np.abs(k - 0.5) * 2  # 0 centre of ray .. 1 edge
    wedge = np.clip((0.55 - tri) / 0.25, 0, 1)
    alt = (np.floor(ang / (2 * math.pi) * rays) % 2 == 0)
    strength = np.where(alt, 1.0, 0.55)
    fall = np.clip(1 - d, 0, 1) ** 1.2 * np.clip(d / 0.08, 0, 1)
    save(rgba_from_alpha(wedge * strength * fall), "rays.png")


def gen_vignette():
    s = 256
    y, x = np.mgrid[0:s, 0:s].astype(np.float32)
    u = (x - (s - 1) / 2) / ((s - 1) / 2)
    v = (y - (s - 1) / 2) / ((s - 1) / 2)
    d = np.sqrt((u * 0.92) ** 2 + (v * 1.08) ** 2)
    a = np.clip((d - 0.45) / 0.85, 0, 1) ** 1.7
    save(rgba_from_alpha(a, (0, 0, 0)), "vignette.png")


def gen_grain():
    s = 256
    nr = np.random.default_rng(7)
    n = nr.normal(0, 1, (s, s)).astype(np.float32)
    arr = np.zeros((s, s, 4), np.uint8)
    lum = np.where(n > 0, 255, 0)
    arr[..., 0] = arr[..., 1] = arr[..., 2] = lum
    arr[..., 3] = (np.clip(np.abs(n) / 3, 0, 1) * 255).astype(np.uint8)
    save(Image.fromarray(arr, "RGBA"), "grain.png")


def gen_scanlines():
    s = 64
    a = np.zeros((s, s), np.float32)
    a[::4, :] = 0.55
    a[1::4, :] = 0.25
    save(rgba_from_alpha(a, (0, 0, 0)), "scanlines.png")


def gen_speedlines():
    s = 512
    img = Image.new("L", (s * 2, s * 2), 0)
    dr = ImageDraw.Draw(img)
    c = s
    for i in range(150):
        ang = rng.random() * math.tau
        w = rng.uniform(0.004, 0.016)
        r0 = rng.uniform(0.42, 0.75) * c
        r1 = c * 1.5
        p0 = (c + math.cos(ang) * r0, c + math.sin(ang) * r0)
        pa = (c + math.cos(ang - w) * r1, c + math.sin(ang - w) * r1)
        pb = (c + math.cos(ang + w) * r1, c + math.sin(ang + w) * r1)
        dr.polygon([p0, pa, pb], fill=rng.randint(120, 255))
    img = img.resize((s, s), Image.LANCZOS)
    d, _, _ = radial(s)
    a = np.asarray(img, np.float32) / 255 * np.clip((d - 0.38) / 0.3, 0, 1)
    save(rgba_from_alpha(a), "speedlines.png")


def fbm(w, h, octaves, seed):
    nr = np.random.default_rng(seed)
    total = np.zeros((h, w), np.float32)
    amp = 1.0
    for o in range(octaves):
        cw, ch = max(2, w >> (octaves - o)), max(2, h >> (octaves - o))
        layer = Image.fromarray((nr.random((ch, cw)) * 255).astype(np.uint8), "L").resize((w, h), Image.BICUBIC)
        total += (np.asarray(layer, np.float32) / 255 - 0.5) * amp
        amp *= 0.55
    total = (total - total.min()) / (total.max() - total.min())
    return total


def gen_nebula():
    w, h = 512, 256
    n1 = fbm(w, h, 6, 11)
    n2 = fbm(w, h, 6, 23)
    n3 = fbm(w, h, 5, 37)
    y = np.linspace(0, 1, h, dtype=np.float32)[:, None]
    band = np.exp(-((y - 0.55) / 0.32) ** 2)
    purple = np.array([120, 70, 190], np.float32)
    teal = np.array([40, 140, 170], np.float32)
    gold = np.array([210, 150, 80], np.float32)
    mix = np.clip(n2 * 1.4 - 0.2, 0, 1)[..., None]
    col = purple * (1 - mix) + teal * mix
    warm = np.clip(n3 * 2 - 1.2, 0, 1)[..., None]
    col = col * (1 - warm * 0.6) + gold * warm * 0.6
    a = np.clip((n1 - 0.38) * 2.2, 0, 1) ** 1.5 * band
    # seamless horizontally: crossfade edges
    edge = 48
    ramp = np.linspace(0, 1, edge, dtype=np.float32)
    a[:, :edge] = a[:, :edge] * ramp + a[:, -edge:][:, ::-1] * (1 - ramp) * 0  # soft edge only
    a[:, :edge] *= ramp
    a[:, -edge:] *= ramp[::-1]
    arr = np.zeros((h, w, 4), np.uint8)
    arr[..., :3] = np.clip(col, 0, 255).astype(np.uint8)
    arr[..., 3] = (a * 255).astype(np.uint8)
    img = Image.fromarray(arr, "RGBA").filter(ImageFilter.GaussianBlur(1.2))
    save(img, "nebula.png")


# ----------------------------------------------------------------------------- display typography

PRESETS = {
    "kill": ([(0, (255, 236, 214)), (0.42, (255, 104, 104)), (1, (168, 18, 40))], (26, 4, 8), (255, 60, 74)),
    "gold": ([(0, (255, 250, 230)), (0.38, (246, 218, 150)), (0.7, (198, 142, 52)), (1, (130, 84, 26))], (28, 18, 6), (255, 181, 94)),
    "revive": ([(0, (255, 255, 255)), (0.45, (255, 238, 176)), (1, (232, 176, 60))], (40, 26, 6), (255, 226, 138)),
    "blast": ([(0, (255, 246, 190)), (0.45, (255, 154, 60)), (1, (200, 64, 22))], (32, 10, 2), (255, 122, 46)),
    "cyan": ([(0, (236, 252, 255)), (0.5, (111, 200, 255)), (1, (36, 112, 210))], (4, 16, 30), (80, 184, 255)),
    "cold": ([(0, (255, 255, 255)), (0.5, (176, 196, 236)), (1, (70, 92, 140))], (5, 7, 15), (143, 176, 255)),
    "psycho": ([(0, (255, 214, 214)), (0.45, (230, 40, 60)), (1, (110, 6, 20))], (20, 2, 4), (220, 24, 42)),
}


def display_title(text, preset, name, size=150, tracking=8, skew=0.13):
    stops, outline, glow = PRESETS[preset]
    font = ImageFont.truetype(BEBAS, size)
    widths = [font.getbbox(ch)[2] - font.getbbox(ch)[0] if ch != " " else size * 0.22 for ch in text]
    adv = [font.getlength(ch) for ch in text]
    total = int(sum(adv) + tracking * (len(text) - 1))
    pad = 44
    W, H = total + pad * 2 + int(size * skew), size + pad * 2
    mask = Image.new("L", (W, H), 0)
    md = ImageDraw.Draw(mask)
    x = pad + size * skew * 0.5
    ascent, descent = font.getmetrics()
    y = pad + (size - ascent) * 0.5 - size * 0.04
    for ch, a in zip(text, adv):
        md.text((x, y), ch, font=font, fill=255)
        x += a + tracking
    # italic shear
    shear = (1, skew, -skew * H * 0.5, 0, 1, 0)
    mask = mask.transform(mask.size, Image.AFFINE, shear, resample=Image.BICUBIC)
    bbox = mask.getbbox()
    top, bot = bbox[1], bbox[3]
    # fill gradient over the glyph height
    grad = Image.new("RGB", (W, H))
    g = vgradient(W, bot - top, stops)
    grad.paste(g, (0, top))
    grad.paste(g.crop((0, 0, W, 1)).resize((W, top)), (0, 0))
    grad.paste(g.crop((0, bot - top - 1, W, bot - top)).resize((W, H - bot)), (0, bot))
    # outline + glow
    stroke = mask.filter(ImageFilter.MaxFilter(9))
    glow_mask = mask.filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.GaussianBlur(14))
    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    glow_layer = Image.new("RGBA", (W, H), glow + (0,))
    glow_layer.putalpha(glow_mask.point(lambda v: int(v * 0.85)))
    out = Image.alpha_composite(out, glow_layer)
    # hard drop shadow for depth
    shadow = Image.new("RGBA", (W, H), outline + (0,))
    shadow.putalpha(ImageChops.offset(stroke, 5, 7).point(lambda v: int(v * 0.8)))
    out = Image.alpha_composite(out, shadow)
    st = Image.new("RGBA", (W, H), outline + (0,))
    st.putalpha(stroke)
    out = Image.alpha_composite(out, st)
    fill = grad.convert("RGBA")
    fill.putalpha(mask)
    out = Image.alpha_composite(out, fill)
    # specular band
    spec = Image.new("L", (W, H), 0)
    sd = ImageDraw.Draw(spec)
    band_y = top + (bot - top) * 0.36
    sd.rectangle([0, band_y, W, band_y + max(2, (bot - top) * 0.035)], fill=150)
    spec = ImageChops.multiply(spec, mask).filter(ImageFilter.GaussianBlur(0.8))
    sl = Image.new("RGBA", (W, H), (255, 255, 255, 0))
    sl.putalpha(spec)
    out = Image.alpha_composite(out, sl)
    out = out.crop(out.getbbox())
    save(out, name)


def gen_brand():
    W, H = 760, 150
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # emblem: art-deco diamond star
    cx, cy, r = 72, 75, 58
    pts = []
    for i in range(16):
        a = -math.pi / 2 + i * math.pi / 8
        rr = r if i % 2 == 0 else r * (0.42 if i % 4 == 1 or i % 4 == 3 else 0.42)
        if i % 4 == 2:
            rr = r * 0.62
        pts.append((cx + math.cos(a) * rr, cy + math.sin(a) * rr))
    glow = Image.new("L", (W, H), 0)
    ImageDraw.Draw(glow).polygon(pts, fill=255)
    glow = glow.filter(ImageFilter.GaussianBlur(10))
    gl = Image.new("RGBA", (W, H), (255, 190, 100, 0))
    gl.putalpha(glow.point(lambda v: int(v * 0.6)))
    img = Image.alpha_composite(img, gl)
    d = ImageDraw.Draw(img)
    d.polygon(pts, fill=BRASS_L + (255,), outline=(60, 40, 10, 255))
    d.ellipse([cx - 13, cy - 13, cx + 13, cy + 13], fill=NAVY + (255,), outline=BRASS_L + (255,), width=3)
    d.ellipse([cx - r - 6, cy - r - 6, cx + r + 6, cy + r + 6], outline=BRASS + (200,), width=2)
    small = ImageFont.truetype(OSWALD, 30)
    big = ImageFont.truetype(BEBAS, 88)
    tx = 158
    label = "STAR - NIGHT   EXPRESS"
    x = tx
    for ch in label:
        d.text((x, 8), ch, font=small, fill=BRASS_L + (255,))
        x += small.getlength(ch) + 3.2
    d.line([tx, 52, W - 20, 52], fill=BRASS + (220,), width=2)
    title = ImageFont.truetype(BEBAS, 88)
    grad = vgradient(W, 90, PRESETS["gold"][0]).convert("RGBA")
    m = Image.new("L", (W, H), 0)
    md = ImageDraw.Draw(m)
    x = tx
    for ch in "MATCH  REPLAY":
        md.text((x, 52), ch, font=title, fill=255)
        x += title.getlength(ch) + 7
    fill = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    fill.paste(grad, (0, 60))
    fill.putalpha(m)
    sh = Image.new("RGBA", (W, H), (20, 12, 4, 0))
    sh.putalpha(ImageChops.offset(m.filter(ImageFilter.MaxFilter(5)), 3, 4).point(lambda v: int(v * 0.8)))
    img = Image.alpha_composite(img, sh)
    img = Image.alpha_composite(img, fill)
    save(img.crop(img.getbbox()), "brand.png")


# ----------------------------------------------------------------------------- ornaments

def gen_ticket():
    W, H = 800, 330
    base = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    paper = vgradient(W, H, [(0, (247, 236, 210)), (1, (232, 215, 178))]).convert("RGBA")
    nr = np.random.default_rng(3)
    noise = (nr.normal(0, 7, (H, W))).astype(np.float32)
    arr = np.asarray(paper, np.float32)
    arr[..., :3] = np.clip(arr[..., :3] + noise[..., None], 0, 255)
    paper = Image.fromarray(arr.astype(np.uint8), "RGBA")
    d = ImageDraw.Draw(paper)
    # guilloche waves
    for k in range(9):
        pts = []
        for x in range(0, 600, 3):
            y = 190 + k * 9 + 14 * math.sin(x / 38 + k * 0.7) * math.cos(x / 97)
            pts.append((x, y))
        d.line(pts, fill=(214, 186, 128, 90), width=1)
    # watermark star
    cx, cy = 300, 190
    star = [(cx + math.cos(-math.pi / 2 + i * math.pi / 5) * (95 if i % 2 == 0 else 38),
             cy + math.sin(-math.pi / 2 + i * math.pi / 5) * (95 if i % 2 == 0 else 38)) for i in range(10)]
    d.polygon(star, outline=(208, 166, 88, 70))
    # header band
    d.rectangle([0, 0, W, 64], fill=NIGHT2 + (255,))
    d.line([0, 64, W, 64], fill=BRASS + (255,), width=3)
    d.line([0, 70, W, 70], fill=BRASS_D + (255,), width=1)
    f = ImageFont.truetype(OSWALD, 26)
    fb = ImageFont.truetype(OSWALD_BOLD, 26)
    lab = ImageFont.truetype(OSWALD, 15)
    x = 30
    for ch in "STAR-NIGHT EXPRESS":
        d.text((x, 14), ch, font=fb, fill=BRASS_L + (255,))
        x += fb.getlength(ch) + 2.5
    d.text((W - 30, 18), "REPLAY  TICKET", font=f, fill=CHAMP + (230,), anchor="ra")
    labels = [((40, 84), "DESTINATION"), ((40, 204), "MODE"), ((250, 204), "DATE"), ((440, 204), "DURATION"),
              ((640, 84), "PASSENGERS"), ((640, 204), "TRAIN No.")]
    for (lx, ly), text in labels:
        x = lx
        for ch in text:
            d.text((x, ly), ch, font=lab, fill=(120, 96, 60, 255))
            x += lab.getlength(ch) + 2.2
    # barcode
    bx = 40
    while bx < 580:
        w = rng.choice([2, 2, 3, 4, 6])
        d.rectangle([bx, 290, bx + w - 1, 316], fill=(40, 34, 30, 220))
        bx += w + rng.choice([2, 3, 4])
    # stub divider
    d.line([610, 76, 610, H], fill=(150, 120, 70, 255), width=1)
    # border
    d.rectangle([8, 76, W - 9, H - 9], outline=(176, 134, 60, 160), width=1)
    for cx2, cy2, sx, sy in [(8, 76, 1, 1), (W - 9, 76, -1, 1), (8, H - 9, 1, -1), (W - 9, H - 9, -1, -1)]:
        for s in (14, 22, 30):
            d.line([cx2, cy2 + sy * s, cx2 + sx * s, cy2 + sy * s], fill=(176, 134, 60, 160), width=1)
            d.line([cx2 + sx * s, cy2, cx2 + sx * s, cy2 + sy * s], fill=(176, 134, 60, 160), width=1)
    # alpha: rounded corners, notches and perforation holes
    m = Image.new("L", (W, H), 0)
    md = ImageDraw.Draw(m)
    md.rounded_rectangle([0, 0, W - 1, H - 1], radius=18, fill=255)
    md.ellipse([610 - 18, -18, 610 + 18, 18], fill=0)
    md.ellipse([610 - 18, H - 18, 610 + 18, H + 18], fill=0)
    for y in range(30, H - 24, 16):
        md.ellipse([610 - 4, y - 4, 610 + 4, y + 4], fill=0)
    paper.putalpha(m)
    # soft drop shadow baked around ticket
    pad = 24
    out = Image.new("RGBA", (W + pad * 2, H + pad * 2), (0, 0, 0, 0))
    sh = Image.new("L", out.size, 0)
    sh.paste(m, (pad + 4, pad + 10))
    sh = sh.filter(ImageFilter.GaussianBlur(10)).point(lambda v: int(v * 0.6))
    shl = Image.new("RGBA", out.size, (0, 0, 0, 0))
    shl.putalpha(sh)
    out = Image.alpha_composite(out, shl)
    out.alpha_composite(paper, (pad, pad))
    save(out, "ticket.png")


def gen_stamp():
    S = 256
    img = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(img)
    d.ellipse([10, 10, S - 10, S - 10], outline=255, width=9)
    d.ellipse([30, 30, S - 30, S - 30], outline=255, width=3)
    for i in range(24):
        a = i * math.tau / 24
        d.line([S / 2 + math.cos(a) * 98, S / 2 + math.sin(a) * 98, S / 2 + math.cos(a) * 104, S / 2 + math.sin(a) * 104], fill=255, width=3)
    star = [(S / 2 + math.cos(-math.pi / 2 + i * math.pi / 5) * (22 if i % 2 == 0 else 9),
             S / 2 - 62 + math.sin(-math.pi / 2 + i * math.pi / 5) * (22 if i % 2 == 0 else 9)) for i in range(10)]
    d.polygon(star, fill=255)
    d.line([40, S / 2 + 50, S - 40, S / 2 + 50], fill=255, width=3)
    d.line([40, S / 2 - 34, S - 40, S / 2 - 34], fill=255, width=3)
    # grunge
    nr = np.random.default_rng(5)
    a = np.asarray(img, np.float32) / 255
    a *= np.clip(nr.random((S, S)) * 1.6, 0.35, 1)
    save(rgba_from_alpha(a), "stamp.png")


def gen_hex_frame():
    S = 256
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    c = S / 2

    def hexpts(r, rot=0):
        return [(c + math.cos(rot + math.pi / 6 + i * math.pi / 3) * r, c + math.sin(rot + math.pi / 6 + i * math.pi / 3) * r) for i in range(6)]

    glow = Image.new("L", (S, S), 0)
    ImageDraw.Draw(glow).polygon(hexpts(118), outline=255, width=10)
    glow = glow.filter(ImageFilter.GaussianBlur(8))
    gl = Image.new("RGBA", (S, S), (255, 190, 100, 0))
    gl.putalpha(glow)
    img = Image.alpha_composite(img, gl)
    m = Image.new("L", (S, S), 0)
    md = ImageDraw.Draw(m)
    md.polygon(hexpts(116), outline=255, width=9)
    md.polygon(hexpts(100), outline=255, width=3)
    for p in hexpts(116):
        md.polygon([(p[0], p[1] - 9), (p[0] + 9, p[1]), (p[0], p[1] + 9), (p[0] - 9, p[1])], fill=255)
    grad = vgradient(S, S, PRESETS["gold"][0]).convert("RGBA")
    grad.putalpha(m)
    sh = Image.new("RGBA", (S, S), (24, 14, 4, 0))
    sh.putalpha(m.filter(ImageFilter.MaxFilter(5)))
    img = Image.alpha_composite(img, sh)
    img = Image.alpha_composite(img, grad)
    save(img, "hex_frame.png")


def gen_laurel():
    # One branch (left), mirrored for the right: a stem on a circular arc with paired leaves.
    W, H = 360, 300
    K = 2
    half = Image.new("L", (W * K // 2, H * K), 0)
    d = ImageDraw.Draw(half)
    cx, cy, R = W * K // 2, H * K * 0.42, H * K * 0.42
    stem = []
    steps = 10
    for i in range(steps + 1):
        a = math.radians(100 + i * 14)  # from bottom (100 deg) round the left side to the top
        stem.append((cx + math.cos(a) * R, cy + math.sin(a) * R * -1 + 0))
    stem = [(cx + math.cos(math.radians(90 + i * 13.5)) * R, cy + math.sin(math.radians(90 + i * 13.5)) * R)
            for i in range(steps + 1)]
    d.line(stem, fill=255, width=9, joint="curve")
    for i in range(1, steps + 1):
        a = math.radians(90 + i * 13.5)
        px, py = cx + math.cos(a) * R, cy + math.sin(a) * R
        tangent = math.degrees(a) + 90
        size = 1 - i / (steps + 6)
        for side in (-1, 1):
            leaf = Image.new("L", (int(96 * size) + 8, int(38 * size) + 4), 0)
            ImageDraw.Draw(leaf).ellipse([2, 2, leaf.width - 3, leaf.height - 3], fill=255)
            ang = -(tangent + side * 38)
            leaf = leaf.rotate(ang, expand=True, resample=Image.BICUBIC)
            off = 30 * size
            ox = px + math.cos(math.radians(tangent + side * 38)) * off - math.cos(math.radians(tangent)) * 0
            oy = py + math.sin(math.radians(tangent + side * 38)) * off
            half.paste(255, (int(ox - leaf.width / 2), int(oy - leaf.height / 2)), leaf)
    full = Image.new("L", (W * K, H * K), 0)
    full.paste(half, (0, 0))
    full.paste(half.transpose(Image.FLIP_LEFT_RIGHT), (W * K // 2, 0))
    m = full.resize((W, H), Image.LANCZOS)
    grad = vgradient(W, H, PRESETS["gold"][0]).convert("RGBA")
    grad.putalpha(m)
    sh = Image.new("RGBA", (W, H), (24, 14, 4, 0))
    sh.putalpha(m.filter(ImageFilter.MaxFilter(3)))
    out = Image.alpha_composite(Image.new("RGBA", (W, H), (0, 0, 0, 0)), sh)
    out = Image.alpha_composite(out, grad)
    save(out.crop(out.getbbox()), "laurel.png")


def gen_crown():
    W, H = 160, 120
    m = Image.new("L", (W * 2, H * 2), 0)
    d = ImageDraw.Draw(m)
    pts = [(30, 200), (20, 70), (90, 135), (160, 30), (230, 135), (300, 70), (290, 200)]
    d.polygon(pts, fill=255)
    d.rectangle([30, 196, 290, 228], fill=255)
    for (x, y) in [(20, 70), (160, 30), (300, 70)]:
        d.ellipse([x - 16, y - 16, x + 16, y + 16], fill=255)
    m = m.resize((W, H), Image.LANCZOS)
    grad = vgradient(W, H, PRESETS["gold"][0]).convert("RGBA")
    grad.putalpha(m)
    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    sh = Image.new("RGBA", (W, H), (28, 16, 4, 0))
    sh.putalpha(m.filter(ImageFilter.MaxFilter(5)))
    out = Image.alpha_composite(out, sh)
    out = Image.alpha_composite(out, grad)
    d2 = ImageDraw.Draw(out)
    for (x, y, col) in [(80, 104, (255, 90, 100)), (45, 104, (111, 200, 255)), (115, 104, (111, 200, 255))]:
        d2.ellipse([x - 7, y - 6, x + 7, y + 6], fill=col + (255,), outline=(40, 20, 8, 255))
    d2.line([18, 97, 142, 97], fill=(120, 80, 20, 255), width=2)
    save(out, "crown.png")


def gen_brush():
    W, H = 1024, 180
    nr = np.random.default_rng(9)
    y, x = np.mgrid[0:H, 0:W].astype(np.float32)
    u = x / (W - 1)
    edge_noise = fbm(W, 8, 4, 41)
    top = 26 + edge_noise[2] * 30
    bot = H - 26 - edge_noise[5] * 30
    body = (y > top[None, :]) & (y < bot[None, :])
    a = body.astype(np.float32)
    streaks = Image.fromarray((nr.random((H, 32)) * 255).astype(np.uint8), "L").resize((W, H), Image.BICUBIC)
    st = np.asarray(streaks, np.float32) / 255
    a *= 0.72 + 0.28 * st
    taper = np.clip(u / 0.12, 0, 1) ** 0.7 * np.clip((1 - u) / 0.18, 0, 1) ** 0.5
    ragged = fbm(W, H, 5, 43)
    a *= np.clip(taper * 1.4 - (1 - ragged) * 0.5 * (1 - taper), 0, 1)
    img = rgba_from_alpha(a).filter(ImageFilter.GaussianBlur(0.8))
    save(img, "brush.png")


# ----------------------------------------------------------------------------- icon atlas (white glyph + dark rim)

def icon_atlas():
    S, C = 256, 64
    m = Image.new("L", (S * 4, S * 4), 0)  # 4x supersample
    d = ImageDraw.Draw(m)
    K = 4

    def cell(i):
        return (i % 4) * C * K, (i // 4) * C * K

    def P(i, x, y):
        ox, oy = cell(i)
        return ox + x * K, oy + y * K

    def poly(i, pts, fill=255):
        d.polygon([P(i, x, y) for x, y in pts], fill=fill)

    def ell(i, x0, y0, x1, y1, fill=255, outline=None, width=0):
        a, b = P(i, x0, y0), P(i, x1, y1)
        d.ellipse([a, b], fill=fill, outline=outline, width=width * K)

    def rect(i, x0, y0, x1, y1, fill=255):
        d.rectangle([P(i, x0, y0), P(i, x1, y1)], fill=fill)

    def line(i, pts, width, fill=255):
        d.line([P(i, x, y) for x, y in pts], fill=fill, width=int(width * K), joint="curve")

    # 0 skull
    ell(0, 14, 8, 50, 42)
    rect(0, 21, 36, 43, 52)
    ell(0, 20, 22, 30, 32, fill=0); ell(0, 34, 22, 44, 32, fill=0)
    poly(0, [(32, 34), (28, 41), (36, 41)], fill=0)
    for x in (25, 31, 37):
        rect(0, x, 46, x + 2, 52, fill=0)
    # 1 crosshair
    ell(1, 12, 12, 52, 52, fill=None, outline=255, width=5)
    for pts in [[(32, 4), (32, 22)], [(32, 42), (32, 60)], [(4, 32), (22, 32)], [(42, 32), (60, 32)]]:
        line(1, pts, 5)
    ell(1, 29, 29, 35, 35)
    # 2 revive: heart with plus
    poly(2, [(32, 56), (8, 30), (8, 18), (18, 10), (32, 18), (46, 10), (56, 18), (56, 30)])
    ell(2, 6, 8, 34, 34); ell(2, 30, 8, 58, 34)
    rect(2, 29, 18, 35, 42, fill=0); rect(2, 20, 27, 44, 33, fill=0)
    # 3 bomb
    ell(3, 10, 18, 50, 58)
    line(3, [(40, 22), (48, 12), (54, 10)], 4)
    poly(3, [(54, 2), (58, 8), (62, 6), (58, 12), (62, 16), (56, 14), (54, 20), (52, 13)])
    ell(3, 18, 28, 26, 36, fill=0)
    # 4 shield
    poly(4, [(32, 4), (56, 12), (54, 34), (32, 60), (10, 34), (8, 12)])
    poly(4, [(32, 12), (48, 18), (46, 34), (32, 52)], fill=0)
    # 5 poison drop
    poly(5, [(32, 4), (48, 32), (16, 32)])
    ell(5, 14, 22, 50, 58)
    ell(5, 22, 36, 30, 46, fill=0)
    # 6 door
    rect(6, 14, 6, 50, 60)
    rect(6, 19, 11, 45, 60, fill=0)
    rect(6, 22, 14, 42, 58)
    ell(6, 35, 33, 40, 38, fill=0)
    # 7 mask
    poly(7, [(6, 20), (58, 20), (54, 40), (40, 46), (32, 38), (24, 46), (10, 40)])
    ell(7, 14, 25, 27, 34, fill=0); ell(7, 37, 25, 50, 34, fill=0)
    # 8 swap arrows
    line(8, [(12, 22), (48, 22)], 6); poly(8, [(46, 12), (58, 22), (46, 32)])
    line(8, [(52, 42), (16, 42)], 6); poly(8, [(18, 32), (6, 42), (18, 52)])
    # 9 bolt
    poly(9, [(38, 2), (12, 36), (30, 36), (24, 62), (52, 26), (34, 26)])
    # 10 potion
    rect(10, 26, 6, 38, 20)
    rect(10, 22, 4, 42, 8)
    ell(10, 12, 18, 52, 60)
    rect(10, 18, 36, 46, 40, fill=0)
    # 11 trap jaw
    poly(11, [(4, 40), (60, 40), (60, 50), (4, 50)])
    for i in range(6):
        x = 6 + i * 9.4
        poly(11, [(x, 40), (x + 4.7, 20), (x + 9.4, 40)])
    ell(11, 26, 44, 38, 56, fill=0)
    # 12 rope
    line(12, [(6, 50), (18, 30), (30, 44), (42, 24), (54, 38), (60, 20)], 6)
    ell(12, 2, 46, 12, 56)
    # 13 bulb
    ell(13, 14, 4, 50, 40)
    rect(13, 23, 36, 41, 50)
    rect(13, 25, 50, 39, 56)
    line(13, [(26, 42), (38, 42)], 2, fill=0); line(13, [(26, 46), (38, 46)], 2, fill=0)
    line(13, [(20, 14), (44, 30)], 4, fill=0)
    # 14 flame
    poly(14, [(32, 2), (50, 26), (54, 42), (44, 58), (20, 58), (10, 42), (16, 26), (24, 34)])
    poly(14, [(32, 28), (42, 44), (36, 54), (28, 54), (22, 44)], fill=0)
    # 15 grenade
    ell(15, 12, 18, 48, 60)
    rect(15, 24, 8, 36, 20)
    line(15, [(36, 12), (48, 6), (54, 14)], 3)
    for yy in (30, 40, 50):
        line(15, [(14, yy), (46, yy)], 2, fill=0)
    m = m.resize((S, S), Image.LANCZOS)
    rim = m.filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.GaussianBlur(0.6))
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    rl = Image.new("RGBA", (S, S), (8, 10, 20, 0))
    rl.putalpha(rim.point(lambda v: int(v * 0.85)))
    out = Image.alpha_composite(out, rl)
    wl = Image.new("RGBA", (S, S), (255, 255, 255, 0))
    wl.putalpha(m)
    out = Image.alpha_composite(out, wl)
    save(out, "icons.png")


def gen_skill_rune():
    """256x256 centered concentric star-rail double ring (white RGB + alpha) for arbitrary role-color tinting."""
    S = 256
    K = 4
    W = S * K
    c = W / 2.0
    m = Image.new("L", (W, W), 0)
    d = ImageDraw.Draw(m)
    outer_r = 118 * K
    inner_r = 82 * K
    # Outer ring and hairline inner companion
    d.ellipse([c - outer_r, c - outer_r, c + outer_r, c + outer_r], outline=255, width=5 * K)
    d.ellipse([c - (outer_r - 9 * K), c - (outer_r - 9 * K), c + (outer_r - 9 * K), c + (outer_r - 9 * K)],
              outline=190, width=2 * K)
    # Inner ring
    d.ellipse([c - inner_r, c - inner_r, c + inner_r, c + inner_r], outline=235, width=4 * K)
    # 24 Art Deco dial ticks between inner and outer rings
    for i in range(24):
        a = i * math.tau / 24.0
        r0 = inner_r + 4 * K
        r1 = outer_r - (12 * K if i % 3 == 0 else 20 * K)
        w = 3 * K if i % 3 == 0 else int(1.6 * K)
        d.line([c + math.cos(a) * r0, c + math.sin(a) * r0,
                c + math.cos(a) * r1, c + math.sin(a) * r1], fill=240 if i % 3 == 0 else 155, width=w)
    # 8-point star-rail octagram inscribed inside inner ring
    for rot in (0.0, math.pi / 4.0):
        sq = [(c + math.cos(rot + i * math.pi / 2.0) * inner_r,
               c + math.sin(rot + i * math.pi / 2.0) * inner_r) for i in range(4)]
        d.polygon(sq, outline=220, width=3 * K)
    # 8 diamond nodes at compass points on outer ring
    for i in range(8):
        a = i * math.tau / 8.0
        px = c + math.cos(a) * (outer_r - 4 * K)
        py = c + math.sin(a) * (outer_r - 4 * K)
        dr = 6 * K if i % 2 == 0 else 4 * K
        d.polygon([(px, py - dr), (px + dr, py), (px, py + dr), (px - dr, py)], fill=255)
    # Central 8-point compass star
    star = []
    for i in range(16):
        a = -math.pi / 2 + i * math.pi / 8.0
        rr = 32 * K if i % 2 == 0 else 11 * K
        star.append((c + math.cos(a) * rr, c + math.sin(a) * rr))
    d.polygon(star, fill=215)
    core = m.resize((S, S), Image.LANCZOS)
    glow = core.filter(ImageFilter.GaussianBlur(3.2)).point(lambda v: int(min(255, v * 0.72)))
    combined = ImageChops.lighter(core, glow)
    save(rgba_from_alpha(np.asarray(combined, np.float32) / 255.0, (255, 255, 255)), "skill_rune.png")


def gen_split_beam():
    """96x720 Star-Night Express gilded brass Art Deco split-screen divider beam."""
    W, H = 96, 720
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    cx = W // 2
    # Soft outer dark shadow & brass aura
    aura = Image.new("L", (W, H), 0)
    ad = ImageDraw.Draw(aura)
    ad.rectangle([cx - 18, 0, cx + 18, H], fill=220)
    aura = aura.filter(ImageFilter.GaussianBlur(10))
    sh = Image.new("RGBA", (W, H), (4, 6, 13, 0))
    sh.putalpha(aura.point(lambda v: int(v * 0.85)))
    img = Image.alpha_composite(img, sh)

    glow = Image.new("L", (W, H), 0)
    ImageDraw.Draw(glow).rectangle([cx - 10, 0, cx + 10, H], fill=255)
    glow = glow.filter(ImageFilter.GaussianBlur(7))
    gl = Image.new("RGBA", (W, H), (244, 216, 148, 0))
    gl.putalpha(glow.point(lambda v: int(v * 0.45)))
    img = Image.alpha_composite(img, gl)

    d = ImageDraw.Draw(img)
    # Dark obsidian inlay core flanked by brushed brass rails
    d.rectangle([cx - 10, 0, cx + 10, H], fill=NIGHT2 + (255,))
    d.rectangle([cx - 12, 0, cx - 8, H], fill=BRASS + (255,))
    d.rectangle([cx + 8, 0, cx + 12, H], fill=BRASS + (255,))
    d.rectangle([cx - 11, 0, cx - 10, H], fill=BRASS_L + (255,))
    d.rectangle([cx + 10, 0, cx + 11, H], fill=BRASS_L + (255,))
    d.line([cx, 0, cx, H], fill=BRASS_D + (220,), width=2)
    # Art Deco chevron accents & brass rivets along the beam
    for y in range(28, H - 20, 44):
        d.polygon([(cx, y - 6), (cx + 5, y), (cx, y + 6), (cx - 5, y)], fill=BRASS_L + (255,), outline=BRASS_D + (255,))
        d.line([cx - 14, y - 16, cx - 10, y - 12], fill=BRASS_L + (220,), width=2)
        d.line([cx + 14, y - 16, cx + 10, y - 12], fill=BRASS_L + (220,), width=2)
    save(img, "split_beam.png")


def gen_split_medallion():
    """192x192 octagonal Art Deco brass weapon emblem medallion mounted on the split-screen beam."""
    S = 192
    K = 2
    W = S * K
    c = W / 2.0

    def oct_pts(r):
        return [(c + math.cos(math.pi / 8 + i * math.pi / 4) * r,
                 c + math.sin(math.pi / 8 + i * math.pi / 4) * r) for i in range(8)]

    glow = Image.new("L", (W, W), 0)
    ImageDraw.Draw(glow).polygon(oct_pts(86 * K), fill=255)
    glow = glow.filter(ImageFilter.GaussianBlur(14))
    out = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    gl = Image.new("RGBA", (W, W), (244, 216, 148, 0))
    gl.putalpha(glow.point(lambda v: int(v * 0.55)))
    out = Image.alpha_composite(out, gl)

    body = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(body)
    d.polygon(oct_pts(80 * K), fill=NAVY + (248,), outline=BRASS_L + (255,), width=7 * K)
    d.polygon(oct_pts(68 * K), fill=NIGHT2 + (240,), outline=BRASS + (230,), width=3 * K)
    for px, py in oct_pts(80 * K):
        r = 6 * K
        d.polygon([(px, py - r), (px + r, py), (px, py + r), (px - r, py)], fill=CHAMP + (255,))
    out = Image.alpha_composite(out, body).resize((S, S), Image.LANCZOS)
    save(out, "split_medallion.png")


def main():
    gen_glow(); gen_ring(); gen_bokeh(); gen_streak(); gen_rays(); gen_vignette(); gen_grain()
    gen_scanlines(); gen_speedlines(); gen_nebula()
    gen_brand(); gen_ticket(); gen_stamp(); gen_hex_frame(); gen_laurel(); gen_crown(); gen_brush(); icon_atlas()
    gen_skill_rune(); gen_split_beam(); gen_split_medallion()
    titles = [
        ("FIRST BLOOD", "kill", "t_first_blood.png"), ("DOUBLE KILL", "kill", "t_double_kill.png"),
        ("TRIPLE KILL", "kill", "t_triple_kill.png"), ("MULTI KILL", "psycho", "t_multi_kill.png"),
        ("FINAL BLOW", "gold", "t_final_blow.png"), ("ELIMINATED", "kill", "t_eliminated.png"),
        ("REVIVED", "revive", "t_revived.png"), ("DETONATION", "blast", "t_detonation.png"),
        ("DEFUSED", "cyan", "t_defused.png"), ("BLACKOUT", "cold", "t_blackout.png"),
        ("PSYCHO", "psycho", "t_psycho.png"), ("MVP", "gold", "t_mvp.png"),
        ("VICTORY", "gold", "t_victory.png"), ("MATCH OVERVIEW", "gold", "t_overview.png"),
        ("DEPARTURE", "gold", "t_departure.png"), ("FINAL STATION", "gold", "t_final_station.png"),
        ("SHIELD BROKEN", "cyan", "t_shield.png"),
        ("APOTHEOSIS", "gold", "t_apotheosis.png"), ("DUAL VISION", "gold", "t_dual_vision.png"),
        ("SHADOW SLASH", "kill", "t_shadow_slash.png"), ("KEY MOVE", "cyan", "t_glimmer.png"),
        ("HIGHLIGHTS", "gold", "t_highlights.png"),
    ]
    for text, preset, name in titles:
        display_title(text, preset, name)
    shutil.copy(BEBAS, FONT_OUT / "bebas.ttf")
    shutil.copy(OSWALD, FONT_OUT / "oswald.ttf")
    print("fonts copied")


main()
