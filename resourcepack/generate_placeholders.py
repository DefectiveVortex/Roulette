#!/usr/bin/env python3
"""Placeholder art and the generated pack files for the Roulette resource pack.

    python3 resourcepack/generate_placeholders.py           # write placeholders, models, item definitions
    python3 resourcepack/generate_placeholders.py --zip     # pack the whole thing: target/Roulette-Textures.zip

Pure Python 3, nothing to install. Every number comes from contract.json (the numbers of docs/ART-CONTRACT.md);
check_art.py, next to this file, checks the result and anybody's own art against the same file.

A texture is only written when it is missing or still byte-for-byte what this script wrote last time (sha256 in
placeholders.json). A file an artist saved over a placeholder no longer matches and is never touched again.
pack.mcmeta and everything under assets/roulette/models and assets/roulette/items belong to this script and are
rewritten on every run.
"""
import hashlib
import json
import math
import sys
import zipfile

sys.dont_write_bytecode = True   # no __pycache__ next to the art
import check_art  # noqa: E402
from check_art import CONTRACT, MANIFEST, PACK, REPO, TEXTURES

# ---- geometry: contract.json ---------------------------------------------------------------------------------
FELT_C, WHEEL_C = CONTRACT["felt"], CONTRACT["wheel"]
CELL = FELT_C["cell"]
FELT_W, FELT_H = FELT_C["size"]
GRID_X, GRID_Y = (round(c * CELL) for c in FELT_C["grid_origin_cells"])
WHEEL = WHEEL_C["size"][0]
R_HUB, R_CONE, R_POCKETS, R_NUMBERS, R_TRACK, R_RIM = (
    WHEEL_C["radii"][ring] for ring in ("hub", "cone", "pockets", "numbers", "track", "rim"))
SMALL = CONTRACT["small"]["size"][0]
REDS = set(CONTRACT["red"])
FELT_LABELS = {"column_1": "2TO1", "column_2": "2TO1", "column_3": "2TO1", "dozen_1": "1ST 12",
               "dozen_2": "2ND 12", "dozen_3": "3RD 12", "low": "1-18", "even": "EVEN", "red": "RED",
               "black": "BLACK", "odd": "ODD", "high": "19-36"}

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
# Same order as the dyes that stand in for chips when a player has no pack: white, red, blue, green, black, purple.
CHIPS = [(236, 236, 236), (196, 40, 44), (44, 92, 196), (60, 170, 70), (36, 36, 40), (128, 60, 170)]

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


def pocket_colour(label):
    if label in ("0", "00"):
        return GREEN
    return RED if int(label) in REDS else BLACK


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
        return check_art.png_bytes(self.w, self.h, self.px)


def shade(rgb, f):
    return tuple(max(0, min(255, round(c * f))) for c in rgb)


# ---- felt ------------------------------------------------------------------------------------------------------
def felt(wheel):
    c = Canvas(FELT_W, FELT_H, CLOTH)
    cells = []  # (x0, y0, x1, y1, fill, label)
    for name, (u0, v0, u1, v1) in {**FELT_C["zero_cells"][wheel], **FELT_C["cells"]}.items():
        numbered = name not in FELT_LABELS
        fill = pocket_colour(name) if numbered else {"red": RED, "black": BLACK}.get(name)
        cells.append((GRID_X + round(u0 * CELL), GRID_Y + round(v0 * CELL),
                      GRID_X + round(u1 * CELL), GRID_Y + round(v1 * CELL), fill, FELT_LABELS.get(name, name)))

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


def rotor_colour(order):
    n = len(order)
    step = math.tau / n
    colours = [pocket_colour(label) for label in order]
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


def wheel_rotor(order):
    c = Canvas(WHEEL, WHEEL)
    c.polar(rotor_colour(order), WHEEL)
    return c


def pack_icon(order):
    rotor = rotor_colour(order)
    c = Canvas(*CONTRACT["pack_icon"]["size"])
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
FLAT = FELT_C["files"] + list(WHEEL_C["files"].values()) + ["marker", "highlight"]
CHIP_IDS = [name for name in CONTRACT["small"]["files"] if name.startswith("chip_")]
MENU = CONTRACT["menu"]["files"]


def texture_path(name):
    return TEXTURES / f"{name}.png"


def placeholders():
    """path -> function returning a Canvas, for every texture; evaluated only for the files that get written."""
    orders = CONTRACT["pockets"]
    art = {
        "felt_european": lambda: felt("european"),
        "felt_american": lambda: felt("american"),
        "wheel_base": wheel_base,
        "wheel_european": lambda: wheel_rotor(orders["european"]),
        "wheel_american": lambda: wheel_rotor(orders["american"]),
        "ball": lambda: small(ball),
        "marker": lambda: small(marker),
        "highlight": lambda: small(highlight),
    }
    for i, rgb in enumerate(CHIPS):
        art[CHIP_IDS[i]] = lambda rgb=rgb: chip(rgb)
    for label in [str(i) for i in range(37)] + ["00"]:
        art[f"menu/n_{label}"] = lambda label=label: icon(pocket_colour(label), label)
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
    files[PACK / "pack.png"] = lambda: pack_icon(orders["european"])
    if set(files) != set(check_art.expected_files()):
        sys.exit("generate_placeholders.py and contract.json disagree about which files exist")
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
    # The ball is the exception: a small square that sits 22/16 block away from the model's centre, towards the
    # wheel picture's 12 o'clock. The display entity stays at the wheel's centre and swings the ball around by
    # rotation alone, which the client interpolates as a true arc. display/WheelView.java (BALL_ORBIT) scales it so
    # that this distance is the ball track; ArtContractTest holds the two together.
    orbit, size = (CONTRACT["world"]["ball_model_sixteenths"][k] for k in ("orbit", "size"))
    write_json(models / "ball.json", {
        "textures": {"0": "roulette:item/ball", "particle": "#0"},
        "elements": [{"from": [8 - size // 2, 8, 8 + orbit - size // 2],
                      "to": [8 + size // 2, 8, 8 + orbit + size // 2], "shade": False,
                      "faces": {"up": {"uv": [16, 16, 0, 0], "texture": "#0"}}}]})
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
    manifest = json.loads(MANIFEST.read_text()) if MANIFEST.exists() else {}
    wrote = kept = 0
    for path, make in placeholders().items():
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


def build_zip():
    """The pack as players download it. Same input gives the same bytes, so the SHA-1 only changes with the art."""
    out = REPO / "target/Roulette-Textures.zip"
    out.parent.mkdir(exist_ok=True)
    files = [PACK / "pack.mcmeta", PACK / "pack.png"] + sorted(p for p in (PACK / "assets").rglob("*") if p.is_file())
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for path in files:
            info = zipfile.ZipInfo(path.relative_to(PACK).as_posix(), date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, path.read_bytes())
    print(f"{out}\n{len(files)} files, {out.stat().st_size} bytes\nsha1: {hashlib.sha1(out.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    if sys.argv[1:] == ["--zip"]:
        sys.exit(build_zip())
    if sys.argv[1:]:
        sys.exit(__doc__)
    generate()
