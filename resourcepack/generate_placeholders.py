#!/usr/bin/env python3
"""Placeholder art and the generated pack files for the Roulette resource pack.

    python3 resourcepack/generate_placeholders.py           # write placeholders, models, item definitions
    python3 resourcepack/generate_placeholders.py --check   # check every texture against docs/ART-CONTRACT.md

Pure Python 3, nothing to install. The geometry is the one in docs/ART-CONTRACT.md; the pocket order and the red
numbers are read from the Java model, so there is no second copy of them here.

A texture is only written when it is missing or still byte-for-byte what this script wrote last time (sha256 in
placeholders.json). A file an artist saved over a placeholder no longer matches and is never touched again.
pack.mcmeta and everything under assets/roulette/models and assets/roulette/items belong to this script and are
rewritten on every run.
"""
import hashlib
import json
import math
import re
import struct
import sys
import zlib
from pathlib import Path

PACK = Path(__file__).resolve().parent
REPO = PACK.parent
MODEL_SRC = REPO / "src/main/java/com/vortex/roulette/model"
TEXTURES = PACK / "assets/roulette/textures/item"
MANIFEST = PACK / "placeholders.json"

# ---- geometry (docs/ART-CONTRACT.md) -------------------------------------------------------------------------
CELL = 64
FELT_W, FELT_H = 16 * CELL, 8 * CELL
GRID_X, GRID_Y = 1 * CELL, 96          # grid origin: cell (1, 1.5) of the canvas
WHEEL = 512
R_HUB, R_CONE, R_POCKETS, R_NUMBERS, R_TRACK, R_RIM = 44, 132, 176, 208, 232, 240
SMALL = 32

# ---- colours -------------------------------------------------------------------------------------------------
CLOTH = (14, 100, 58)
LINE = (232, 222, 186)
WHITE = (245, 245, 245)
RED = (186, 32, 38)
BLACK = (28, 28, 30)
GREEN = (20, 150, 80)
WOOD = (120, 76, 40)
WOOD_DARK = (74, 46, 24)
WOOD_LIGHT = (164, 112, 62)
METAL = (196, 196, 204)
GOLD = (214, 172, 60)
CHIPS = [(236, 236, 236), (196, 40, 44), (44, 92, 196), (36, 36, 40), (128, 60, 170), (222, 176, 50)]

FONT = {  # 5 x 7
    "0": ".###. #...# #..## #.#.# ##..# #...# .###.", "1": "..#.. .##.. ..#.. ..#.. ..#.. ..#.. .###.",
    "2": ".###. #...# ....# ...#. ..#.. .#... #####", "3": "##### ...#. ..#.. ...#. ....# #...# .###.",
    "4": "...#. ..##. .#.#. #..#. ##### ...#. ...#.", "5": "##### #.... ####. ....# ....# #...# .###.",
    "6": "..##. .#... #.... ####. #...# #...# .###.", "7": "##### ....# ...#. ..#.. .#... .#... .#...",
    "8": ".###. #...# #...# .###. #...# #...# .###.", "9": ".###. #...# #...# .#### ....# ...#. .##..",
    "A": ".###. #...# #...# ##### #...# #...# #...#", "B": "####. #...# #...# ####. #...# #...# ####.",
    "C": ".###. #...# #.... #.... #.... #...# .###.", "D": "####. #...# #...# #...# #...# #...# ####.",
    "E": "##### #.... #.... ####. #.... #.... #####", "K": "#...# #..#. #.#.. ##... #.#.. #..#. #...#",
    "L": "#.... #.... #.... #.... #.... #.... #####", "N": "#...# ##..# #.#.# #..## #...# #...# #...#",
    "O": ".###. #...# #...# #...# #...# #...# .###.", "R": "####. #...# #...# ####. #.#.. #..#. #...#",
    "S": ".#### #.... #.... .###. ....# ....# ####.", "T": "##### ..#.. ..#.. ..#.. ..#.. ..#.. ..#..",
    "V": "#...# #...# #...# #...# #...# .#.#. ..#..", "-": "..... ..... ..... ##### ..... ..... .....",
    " ": "..... ..... ..... ..... ..... ..... .....",
}
FONT = {ch: rows.split() for ch, rows in FONT.items()}


def text_bits(text):
    """Rows of booleans for a line of text, glyphs one column apart."""
    rows = []
    for y in range(7):
        row = []
        for i, ch in enumerate(text):
            if i:
                row.append(False)
            row.extend(c == "#" for c in FONT[ch][y])
        rows.append(row)
    return rows


# ---- the Java model is the single source for pocket order and colours ------------------------------------------
def read_model():
    wheel_src = (MODEL_SRC / "WheelType.java").read_text()
    pocket_src = (MODEL_SRC / "Pocket.java").read_text()
    orders = {}
    for name in ("EUROPEAN", "AMERICAN"):
        m = re.search(name + r'\("([0-9 ]+)"\)', wheel_src)
        if not m:
            sys.exit(f"cannot find the {name} pocket order in WheelType.java")
        orders[name.lower()] = m.group(1).split()
    m = re.search(r"RED\s*=\s*Set\.of\(([0-9,\s]+)\)", pocket_src)
    if not m:
        sys.exit("cannot find the red numbers in Pocket.java")
    reds = {int(n) for n in m.group(1).split(",")}
    if (len(orders["european"]), len(orders["american"]), len(reds)) != (37, 38, 18):
        sys.exit("pocket order or red set has the wrong size")
    return orders, reds


def pocket_colour(label, reds):
    if label in ("0", "00"):
        return GREEN
    return RED if int(label) in reds else BLACK


# ---- a small RGBA canvas ---------------------------------------------------------------------------------------
class Canvas:
    def __init__(self, w, h, fill=None):
        self.w, self.h = w, h
        self.px = bytearray((bytes(fill) + b"\xff" if fill else b"\0\0\0\0") * (w * h))

    def rect(self, x0, y0, x1, y1, rgb, a=255):
        x0, y0, x1, y1 = max(0, x0), max(0, y0), min(self.w, x1), min(self.h, y1)
        if x0 >= x1:
            return
        row = (bytes(rgb) + bytes([a])) * (x1 - x0)
        for y in range(y0, y1):
            o = (y * self.w + x0) * 4
            self.px[o:o + len(row)] = row

    def text(self, cx, cy, text, scale, rgb):
        bits = text_bits(text)
        x0 = round(cx - len(bits[0]) * scale / 2)
        y0 = round(cy - 7 * scale / 2)
        for gy, row in enumerate(bits):
            for gx, on in enumerate(row):
                if on:
                    self.rect(x0 + gx * scale, y0 + gy * scale, x0 + (gx + 1) * scale, y0 + (gy + 1) * scale, rgb)

    def fit_text(self, cx, cy, text, max_w, rgb, scales=(4, 3, 2, 1)):
        width = len(text) * 6 - 1
        self.text(cx, cy, text, next((s for s in scales if width * s <= max_w), 1), rgb)

    def polar(self, colour_at, units):
        """Fill from colour_at(r, angle): r in contract pixels from the centre, angle clockwise from 12 o'clock.
        colour_at returns (r, g, b), (r, g, b, a) or None for transparent. `units` is the contract width."""
        k = units / self.w
        c = self.w / 2
        px = self.px
        o = 0
        for y in range(self.h):
            dy = (y + 0.5 - c) * k
            for x in range(self.w):
                dx = (x + 0.5 - c) * k
                col = colour_at(math.hypot(dx, dy), math.atan2(dx, -dy) % math.tau)
                if col:
                    px[o:o + 4] = bytes(col) if len(col) == 4 else bytes(col) + b"\xff"
                o += 4

    def png(self):
        raw = b"".join(b"\0" + bytes(self.px[y * self.w * 4:(y + 1) * self.w * 4]) for y in range(self.h))

        def chunk(kind, data):
            return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

        return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", self.w, self.h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def shade(rgb, f):
    return tuple(max(0, min(255, round(c * f))) for c in rgb)


# ---- felt ------------------------------------------------------------------------------------------------------
def felt(american, reds):
    c = Canvas(FELT_W, FELT_H, CLOTH)
    cells = []  # (x0, y0, x1, y1, fill, label)

    def cell(u0, v0, u1, v1, fill, label):
        cells.append((GRID_X + round(u0 * CELL), GRID_Y + round(v0 * CELL),
                      GRID_X + round(u1 * CELL), GRID_Y + round(v1 * CELL), fill, label))

    if american:
        cell(0, 0, 1, 1.5, GREEN, "00")
        cell(0, 1.5, 1, 3, GREEN, "0")
    else:
        cell(0, 0, 1, 3, GREEN, "0")
    for n in range(1, 37):
        s, t = (n - 1) // 3, 2 - (n - 1) % 3
        cell(1 + s, t, 2 + s, t + 1, pocket_colour(str(n), reds), str(n))
    for i in range(3):
        cell(13, i, 14, i + 1, None, "2TO1")
        cell(1 + 4 * i, 3, 5 + 4 * i, 4, None, ("1ST 12", "2ND 12", "3RD 12")[i])
    for i, label in enumerate(("1-18", "EVEN", "RED", "BLACK", "ODD", "19-36")):
        cell(1 + 2 * i, 4, 3 + 2 * i, 5, {"RED": RED, "BLACK": BLACK}.get(label), label)

    for x0, y0, x1, y1, fill, label in cells:
        if fill:
            c.rect(x0, y0, x1, y1, fill)
    for x0, y0, x1, y1, fill, label in cells:  # 4 px lines centred on the cell boundaries
        c.rect(x0 - 2, y0 - 2, x1 + 2, y0 + 2, LINE)
        c.rect(x0 - 2, y1 - 2, x1 + 2, y1 + 2, LINE)
        c.rect(x0 - 2, y0 - 2, x0 + 2, y1 + 2, LINE)
        c.rect(x1 - 2, y0 - 2, x1 + 2, y1 + 2, LINE)
        c.fit_text((x0 + x1) / 2, (y0 + y1) / 2, label, x1 - x0 - 14, WHITE)
    return c


# ---- wheel -----------------------------------------------------------------------------------------------------
def base_colour(r, a):
    if r >= R_RIM:
        return CLOTH
    if r >= R_TRACK:
        return WOOD_DARK
    if r >= R_NUMBERS:
        return shade(WOOD_LIGHT, 0.9 + 0.2 * (r - R_NUMBERS) / (R_TRACK - R_NUMBERS))
    return (16, 16, 18)


def rotor_colour(order, reds):
    n = len(order)
    step = math.tau / n
    colours = [pocket_colour(label, reds) for label in order]
    labels = [text_bits(label) for label in order]
    mid = (R_POCKETS + R_NUMBERS) / 2

    def at(r, a):
        if r >= R_NUMBERS:
            return None
        if r < R_HUB:
            return shade(GOLD, 0.7) if r > R_HUB - 4 or r < 6 else GOLD
        if r < R_CONE:
            return WOOD_DARK if r > R_CONE - 4 else shade(WOOD, 0.85 + 0.3 * (r - R_HUB) / (R_CONE - R_HUB))
        k = round(a / step) % n
        d = (a - k * step + math.pi) % math.tau - math.pi   # angle from the pocket's centre line
        t = r * d                                            # clockwise distance from it, in pixels
        if abs(d) > step / 2 - 1.5 / r or abs(r - R_POCKETS) < 2 or r > R_NUMBERS - 2:
            return METAL
        if r < R_POCKETS:
            return shade(colours[k], 0.6)
        bits = labels[k]                                     # numerals stand with their heads to the rim
        gx, gy = math.floor(t / 2 + len(bits[0]) / 2), math.floor(3.5 - (r - mid) / 2)
        if 0 <= gy < 7 and 0 <= gx < len(bits[0]) and bits[gy][gx]:
            return WHITE
        return colours[k]

    return at


def wheel_base():
    c = Canvas(WHEEL, WHEEL)
    c.polar(base_colour, WHEEL)
    return c


def wheel_rotor(order, reds):
    c = Canvas(WHEEL, WHEEL)
    c.polar(rotor_colour(order, reds), WHEEL)
    return c


def pack_icon(order, reds):
    rotor = rotor_colour(order, reds)
    c = Canvas(256, 256)
    c.polar(lambda r, a: rotor(r, a) or base_colour(r, a), WHEEL)
    return c


# ---- small pieces ----------------------------------------------------------------------------------------------
def small(colour_at):
    c = Canvas(SMALL, SMALL)
    c.polar(colour_at, SMALL)
    return c


def chip(rgb):
    dark = shade(rgb, 0.55) if sum(rgb) > 200 else shade(rgb, 1.8)
    stripe = WHITE if sum(rgb) < 600 else (60, 60, 70)

    def at(r, a):
        if r > 15.5:
            return None
        if r > 14.5 or 10 < r <= 11:
            return dark
        if r > 11:
            return stripe if (a * 6 / math.tau) % 1 < 0.5 else rgb
        return rgb
    return small(at)


def ball(r, a):
    if r > 15.5:
        return None
    x, y = r * math.sin(a), -r * math.cos(a)
    if math.hypot(x + 5, y + 5) < 4:
        return (255, 255, 255)
    return (150, 150, 158) if r > 13.5 else (228, 228, 232)


def marker(r, a):
    if r > 15.5 or r < 8.5:
        return None
    return shade(GOLD, 0.55) if r > 14.5 or r < 9.5 else GOLD


def highlight(r, a):
    return (255, 255, 255, 170) if 11 <= r <= 15 else None


def icon(fill, *lines):
    c = Canvas(SMALL, SMALL, shade(fill, 0.6))
    c.rect(2, 2, SMALL - 2, SMALL - 2, fill)
    for i, line in enumerate(lines):
        cy = SMALL / 2 + (i - (len(lines) - 1) / 2) * 15
        c.fit_text(SMALL / 2, cy, line, 26, WHITE, scales=(3, 2, 1) if len(lines) == 1 else (2, 1))
    return c


# ---- what the pack contains ------------------------------------------------------------------------------------
FLAT = ["felt_european", "felt_american", "wheel_base", "wheel_european", "wheel_american", "ball",
        "marker", "highlight"]
CHIP_IDS = [f"chip_{i}" for i in range(1, 7)]
MENU = ([f"n_{i}" for i in range(37)] + ["n_00"] + [f"dozen_{i}" for i in (1, 2, 3)]
        + [f"column_{i}" for i in (1, 2, 3)] + ["red", "black", "odd", "even", "low", "high"])

SIZES = {"felt_european": (FELT_W, FELT_H), "felt_american": (FELT_W, FELT_H), "wheel_base": (WHEEL, WHEEL),
         "wheel_european": (WHEEL, WHEEL), "wheel_american": (WHEEL, WHEEL)}
HALF_OK = set(SIZES)   # the contract allows these at exactly half size, all together


def texture_path(name):
    return TEXTURES / f"{name}.png"


def placeholders(orders, reds):
    """name -> function returning a Canvas, for every texture; evaluated only for the files that get written."""
    art = {
        "felt_european": lambda: felt(False, reds),
        "felt_american": lambda: felt(True, reds),
        "wheel_base": wheel_base,
        "wheel_european": lambda: wheel_rotor(orders["european"], reds),
        "wheel_american": lambda: wheel_rotor(orders["american"], reds),
        "ball": lambda: small(ball),
        "marker": lambda: small(marker),
        "highlight": lambda: small(highlight),
    }
    for i, rgb in enumerate(CHIPS):
        art[CHIP_IDS[i]] = lambda rgb=rgb: chip(rgb)
    for label in [str(i) for i in range(37)] + ["00"]:
        art[f"menu/n_{label}"] = lambda label=label: icon(pocket_colour(label, reds), label)
    for i, (lo, hi) in enumerate(((1, 12), (13, 24), (25, 36)), 1):
        art[f"menu/dozen_{i}"] = lambda lo=lo, hi=hi: icon(CLOTH, str(lo), str(hi))
        art[f"menu/column_{i}"] = lambda i=i: icon(CLOTH, f"C{i}")
    art["menu/red"] = lambda: icon(RED, "RED")
    art["menu/black"] = lambda: icon(BLACK, "BLACK")
    art["menu/odd"] = lambda: icon(CLOTH, "ODD")
    art["menu/even"] = lambda: icon(CLOTH, "EVEN")
    art["menu/low"] = lambda: icon(CLOTH, "1", "18")
    art["menu/high"] = lambda: icon(CLOTH, "19", "36")
    files = {texture_path(name): make for name, make in art.items()}
    files[PACK / "pack.png"] = lambda: pack_icon(orders["european"], reds)
    return files


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n")


def write_pack_files():
    # Formats: 46 is 1.21.4. 1.21.4-1.21.8 read pack_format + supported_formats, 1.21.9 and newer read
    # min_format/max_format. pack_format has to lie inside supported_formats or the old clients drop the range.
    write_json(PACK / "pack.mcmeta", {"pack": {
        "description": "Roulette: felt, wheel and chips", "pack_format": 46, "supported_formats": [46, 64],
        "min_format": 46, "max_format": 120}})
    models, items = PACK / "assets/roulette/models/item", PACK / "assets/roulette/items"
    # One flat square, 1 x 1 block, face up, centred on the entity. An ItemDisplay draws its item turned half a
    # turn about the vertical axis, so the uv is turned half a turn here to cancel it: on a display with no
    # rotation the texture's +x points to world +X and its +y to world +Z. If the felt ever shows up with the
    # zero at the wrong end, this uv is the one place to change (to [0, 0, 16, 16]).
    write_json(models / "flat.json", {
        "gui_light": "front", "textures": {"particle": "#0"},
        "elements": [{"from": [0, 8, 0], "to": [16, 8, 16], "shade": False,
                      "faces": {"up": {"uv": [16, 16, 0, 0], "texture": "#0"}}}]})
    for name in FLAT + CHIP_IDS:
        write_json(models / f"{name}.json",
                   {"parent": "roulette:item/flat", "textures": {"0": f"roulette:item/{name}"}})
    for name in FLAT:
        write_json(items / f"{name}.json", {"model": {"type": "minecraft:model", "model": f"roulette:item/{name}"}})
    for name in CHIP_IDS:
        # Flat on a display (context "none"); an ordinary item sprite in the hotbar, in the hand and on the ground.
        write_json(models / f"{name}_icon.json",
                   {"parent": "minecraft:item/generated", "textures": {"layer0": f"roulette:item/{name}"}})
        write_json(items / f"{name}.json", {"model": {
            "type": "minecraft:select", "property": "minecraft:display_context",
            "cases": [{"when": "none", "model": {"type": "minecraft:model", "model": f"roulette:item/{name}"}}],
            "fallback": {"type": "minecraft:model", "model": f"roulette:item/{name}_icon"}}})
    for name in MENU:
        write_json(models / f"menu/{name}.json",
                   {"parent": "minecraft:item/generated", "textures": {"layer0": f"roulette:item/menu/{name}"}})
        write_json(items / f"menu/{name}.json",
                   {"model": {"type": "minecraft:model", "model": f"roulette:item/menu/{name}"}})


def generate():
    orders, reds = read_model()
    manifest = json.loads(MANIFEST.read_text()) if MANIFEST.exists() else {}
    wrote = kept = 0
    for path, make in placeholders(orders, reds).items():
        key = path.relative_to(PACK).as_posix()
        if path.exists() and hashlib.sha256(path.read_bytes()).hexdigest() != manifest.get(key):
            manifest.pop(key, None)   # somebody's own art: never touched again
            kept += 1
            continue
        data = make().png()
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists() or path.read_bytes() != data:
            path.write_bytes(data)
            wrote += 1
        manifest[key] = hashlib.sha256(data).hexdigest()
    MANIFEST.write_text(json.dumps(dict(sorted(manifest.items())), indent=1) + "\n")
    write_pack_files()
    print(f"placeholders written: {wrote}, still placeholders: {len(manifest)}, hand-made files left alone: {kept}")


# ---- --check ---------------------------------------------------------------------------------------------------
def read_png(path):
    """(width, height, rgba bytes) of an 8-bit RGBA, non-interlaced PNG; raises ValueError otherwise."""
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG file")
    pos, idat, head = 8, b"", None
    while pos < len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            head = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            idat += body
        pos += 12 + length
    w, h, depth, colour, _, _, interlace = head
    if (depth, colour, interlace) != (8, 6, 0):
        raise ValueError("must be saved as RGBA, 8 bits per channel, not interlaced and not indexed")
    raw, stride = zlib.decompress(idat), w * 4
    out, prev = bytearray(), bytearray(stride)
    for y in range(h):
        f = raw[y * (stride + 1)]
        row = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        if f == 1:
            for i in range(4, stride):
                row[i] = (row[i] + row[i - 4]) & 255
        elif f == 2:
            for i in range(stride):
                row[i] = (row[i] + prev[i]) & 255
        elif f == 3:
            for i in range(stride):
                row[i] = (row[i] + ((row[i - 4] if i >= 4 else 0) + prev[i] >> 1)) & 255
        elif f == 4:
            for i in range(stride):
                a, b, c = (row[i - 4] if i >= 4 else 0), prev[i], (prev[i - 4] if i >= 4 else 0)
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                row[i] = (row[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        out += row
        prev = row
    return w, h, bytes(out)


def check():
    problems, scale = [], set()
    files = [texture_path(n) for n in FLAT + CHIP_IDS + [f"menu/{m}" for m in MENU]] + [PACK / "pack.png"]
    for path in files:
        key = path.relative_to(PACK).as_posix()
        name = path.relative_to(TEXTURES).with_suffix("").as_posix() if TEXTURES in path.parents else "pack"
        if not path.exists():
            problems.append(f"{key}: missing")
            continue
        try:
            w, h, px = read_png(path)
        except ValueError as e:
            problems.append(f"{key}: {e}")
            continue
        want = SIZES.get(name, (256, 256) if name == "pack" else (SMALL, SMALL))
        if (w, h) == want:
            if name in HALF_OK:
                scale.add(1)
        elif name in HALF_OK and (w * 2, h * 2) == want:
            scale.add(2)
        else:
            problems.append(f"{key}: is {w} x {h}, should be {want[0]} x {want[1]}")
            continue
        alpha = px[3::4]
        if name != "highlight" and name != "pack" and alpha.count(0) + alpha.count(255) != len(alpha):
            problems.append(f"{key}: has half-transparent pixels (only highlight.png may)")
        if name == "wheel_base" and alpha.count(255) != len(alpha):
            problems.append(f"{key}: must be opaque everywhere, corners included")
        if name in ("wheel_european", "wheel_american"):
            c, limit = w / 2, (R_NUMBERS + 1.5) * w / WHEEL
            if any(alpha[y * w + x] and math.hypot(x + 0.5 - c, y + 0.5 - c) > limit
                   for y in range(h) for x in range(w)):
                problems.append(f"{key}: must be transparent beyond {R_NUMBERS * w // WHEEL} px from the centre")
    if len(scale) > 1:
        problems.append("felt and wheel files mix full size and half size: use one for all five")
    print("\n".join(problems) if problems else "all textures fit the art contract")
    return 1 if problems else 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--check"]:
        sys.exit(check())
    if sys.argv[1:]:
        sys.exit(__doc__)
    generate()
