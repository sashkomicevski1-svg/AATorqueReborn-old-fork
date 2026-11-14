"""Render the "Mazda Style" theme drawables from code.

All artwork is original and drawn here: no logo, wordmark, font or copied graphics. The look
follows the handoff palette: a deep black face, a satin-silver bezel, thin tick rings, sparse
marks, and a slim tapered crimson needle with a soft glow.

Geometry matches the other themes. The canvas is 480x480 and the needle pivots at
(240, 240) pointing up. The scale runs over 270 degrees, from 135 to 405 in SpeedView's
convention (0 = east, clockwise). There are 9 labelled ticks (TorqueGauge.NUM_TICKS), so a
major mark sits every 33.75 degrees with four minor marks between.

Needs Pillow. Run from the repository root:
    python tools/make_mazda_theme.py
"""

import math
import pathlib

from PIL import Image, ImageDraw, ImageFilter, ImageFont

RES = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res"
OUT = RES / "drawable"
FONT = RES / "font/barlow_semi_condensed.ttf"
BG_W, BG_H = 800, 400
SIZE = 480
SS = 4  # supersampling factor for smooth edges
C = SIZE / 2

FACE_EDGE = (0x0B, 0x0C, 0x0E)
FACE_CENTRE = (0x1A, 0x1C, 0x20)
RING = (0x2A, 0x2D, 0x33)
BEZEL_LIGHT = (0xB8, 0xBC, 0xC2)
BEZEL_DARK = (0x5E, 0x63, 0x6B)
MAJOR = (0xE6, 0xE8, 0xEB)
MINOR = (0x8A, 0x8F, 0x98)
CRIMSON = (0xE3, 0x24, 0x2B)

START_DEG = 135.0
SWEEP_DEG = 270.0
LABELS = 9
MINOR_PER_GAP = 4
RED_ZONE = 0.85  # last 15% of the scale is marked in crimson

R_OUTER = 236  # bezel outer edge
R_BEZEL_IN = 228
R_FACE = 226
R_TICK_OUT = 214
R_MAJOR_IN = 194
R_MINOR_IN = 204
R_INNER_RING = 118


def canvas() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new("RGBA", (SIZE * SS, SIZE * SS), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def finish(img: Image.Image) -> Image.Image:
    return img.resize((SIZE, SIZE), Image.LANCZOS)


def circle(draw, r, fill=None, outline=None, width=0):
    s = SS
    draw.ellipse(
        [(C - r) * s, (C - r) * s, (C + r) * s, (C + r) * s],
        fill=fill,
        outline=outline,
        width=int(width * s),
    )


def radial_face() -> Image.Image:
    """Dark face, slightly lighter in the centre."""
    face = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = face.load()
    for y in range(SIZE):
        for x in range(SIZE):
            d = math.hypot(x + 0.5 - C, y + 0.5 - C) / R_FACE
            if d <= 1.0:
                t = min(1.0, d)
                col = tuple(int(FACE_CENTRE[i] + (FACE_EDGE[i] - FACE_CENTRE[i]) * t) for i in range(3))
                px[x, y] = (*col, 255)
    return face


def bezel() -> Image.Image:
    """Satin ring: light at the top left, dark at the bottom right."""
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            d = math.hypot(x + 0.5 - C, y + 0.5 - C)
            if R_BEZEL_IN <= d <= R_OUTER:
                # Light from the top left.
                t = ((x - C) + (y - C)) / (2 * R_OUTER) + 0.5
                t = max(0.0, min(1.0, t))
                col = tuple(int(BEZEL_LIGHT[i] + (BEZEL_DARK[i] - BEZEL_LIGHT[i]) * t) for i in range(3))
                # Soften both edges.
                edge = min(d - R_BEZEL_IN, R_OUTER - d)
                alpha = int(255 * max(0.0, min(1.0, edge / 1.2)))
                px[x, y] = (*col, alpha)
    return img


def polar(r: float, deg: float) -> tuple[float, float]:
    a = math.radians(deg)
    return (C + r * math.cos(a)) * SS, (C + r * math.sin(a)) * SS


def ticks() -> Image.Image:
    img, draw = canvas()
    gaps = LABELS - 1
    steps = gaps * (MINOR_PER_GAP + 1)
    for i in range(steps + 1):
        frac = i / steps
        deg = START_DEG + SWEEP_DEG * frac
        major = i % (MINOR_PER_GAP + 1) == 0
        red = frac >= RED_ZONE - 1e-9
        colour = CRIMSON if red else (MAJOR if major else MINOR)
        r_in = R_MAJOR_IN if major else R_MINOR_IN
        width = 4 if major else 2
        draw.line([polar(r_in, deg), polar(R_TICK_OUT, deg)], fill=(*colour, 255), width=width * SS)
    # Thin track under the ticks, from start to end of the scale.
    box = [(C - R_TICK_OUT - 3) * SS, (C - R_TICK_OUT - 3) * SS, (C + R_TICK_OUT + 3) * SS, (C + R_TICK_OUT + 3) * SS]
    draw.arc(box, START_DEG, START_DEG + SWEEP_DEG * RED_ZONE, fill=(*RING, 255), width=2 * SS)
    draw.arc(box, START_DEG + SWEEP_DEG * RED_ZONE, START_DEG + SWEEP_DEG, fill=(*CRIMSON, 255), width=2 * SS)
    return finish(img)


def inner_ring() -> Image.Image:
    img, draw = canvas()
    circle(draw, R_INNER_RING, outline=(*RING, 255), width=2)
    return finish(img)


def dial(with_ticks: bool, with_inner: bool) -> Image.Image:
    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    out.alpha_composite(radial_face())
    # Dark ring just inside the bezel.
    ring, draw = canvas()
    circle(draw, R_FACE - 2, outline=(*RING, 255), width=3)
    out.alpha_composite(finish(ring))
    if with_ticks:
        out.alpha_composite(ticks())
    if with_inner:
        out.alpha_composite(inner_ring())
    out.alpha_composite(bezel())
    return out


def needle() -> Image.Image:
    """Slim tapered crimson needle with a soft glow.

    It starts just outside the inner ring instead of at the pivot, so the value printed in
    the centre of the dial is never covered.
    """
    img, draw = canvas()
    tip_y = 44
    base_y = C - (R_INNER_RING + 6)
    base_half = 4.5
    tip_half = 1.2
    s = SS
    shape = [
        ((C - base_half) * s, base_y * s),
        ((C - tip_half) * s, tip_y * s),
        ((C + tip_half) * s, tip_y * s),
        ((C + base_half) * s, base_y * s),
    ]
    draw.polygon(shape, fill=(*CRIMSON, 255))
    blade = finish(img)

    glow = blade.copy()
    r, g, b, a = glow.split()
    a = a.filter(ImageFilter.GaussianBlur(6)).point(lambda v: int(v * 0.55))
    glow = Image.merge("RGBA", (r, g, b, a))

    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    out.alpha_composite(glow)
    out.alpha_composite(blade)
    return out


def thumbnail(dial_img: Image.Image, needle_img: Image.Image) -> Image.Image:
    # Needle at 30% of the scale. The needle image points up (270 degrees in SpeedView
    # terms), so rotate it to the target angle.
    angle = START_DEG + SWEEP_DEG * 0.3
    rotated = needle_img.rotate(-(angle - 270), resample=Image.BICUBIC, center=(C, C))
    out = dial_img.copy()
    out.alpha_composite(rotated)
    return out.resize((128, 128), Image.LANCZOS)


def background() -> Image.Image:
    """800x400 car background: graphite black, a soft lift behind the gauges, two faint satin
    sweeps and a low crimson glow. Kept dark so gauges stay readable in sunlight."""
    w, h = BG_W * 2, BG_H * 2
    img = Image.new("RGB", (w, h), FACE_EDGE)
    px = img.load()
    for y in range(h):
        for x in range(w):
            # Elliptical lift, centred slightly above the middle where the gauges sit.
            d = math.hypot((x - w / 2) / (w * 0.55), (y - h * 0.45) / (h * 0.75))
            t = max(0.0, 1.0 - d)
            px[x, y] = tuple(int(FACE_EDGE[i] + (FACE_CENTRE[i] - FACE_EDGE[i]) * t) for i in range(3))
    img = img.convert("RGBA")

    sweeps = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(sweeps)
    # Two long, shallow arcs crossing the screen: a hint of reflected light, nothing more.
    draw.arc([-w * 0.3, h * 0.10, w * 1.3, h * 2.2], 200, 340, fill=(255, 255, 255, 22), width=5)
    draw.arc([-w * 0.2, h * 0.30, w * 1.2, h * 2.6], 205, 335, fill=(255, 255, 255, 12), width=3)
    sweeps = sweeps.filter(ImageFilter.GaussianBlur(3))
    img.alpha_composite(sweeps)

    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    gdraw = ImageDraw.Draw(glow)
    gdraw.ellipse([w * 0.25, h * 0.92, w * 0.75, h * 1.12], fill=(*CRIMSON, 70))
    glow = glow.filter(ImageFilter.GaussianBlur(40))
    img.alpha_composite(glow)
    return img.resize((BG_W, BG_H), Image.LANCZOS).convert("RGB")


def background_thumb(bg: Image.Image) -> Image.Image:
    side = BG_H
    left = (BG_W - side) // 2
    return bg.crop((left, 0, left + side, side)).resize((128, 128), Image.LANCZOS)


def font_thumb() -> Image.Image:
    """Same layout as the other font thumbnails: "123" over "Abc", black on transparent (the
    app tints it)."""
    img = Image.new("RGBA", (128 * SS, 128 * SS), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    font = ImageFont.truetype(str(FONT), 52 * SS)
    draw.text((10 * SS, -6 * SS), "123", font=font, fill=(0, 0, 0, 255))
    draw.text((10 * SS, 58 * SS), "Abc", font=font, fill=(0, 0, 0, 255))
    return img.resize((128, 128), Image.LANCZOS)


def main() -> None:
    marks = dial(with_ticks=True, with_inner=True)
    empty = dial(with_ticks=False, with_inner=True)
    blank = dial(with_ticks=False, with_inner=False)
    pointer = needle()
    files = {
        "dial_background_marks_mazda.png": marks,
        "dial_background_empty_mazda.png": empty,
        "dial_background_blank_mazda.png": blank,
        "needle_mazda.png": pointer,
        "ic_theme_mazda.png": thumbnail(marks, pointer),
        "ic_font_barlow.png": font_thumb(),
    }
    bg = background()
    files["background_incar_mazda.png"] = bg
    files["thumb_background_incar_mazda.png"] = background_thumb(bg)
    for name, image in files.items():
        image.save(OUT / name, optimize=True)
        print("wrote", OUT / name)


if __name__ == "__main__":
    main()
