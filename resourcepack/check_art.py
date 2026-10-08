#!/usr/bin/env python3
"""Checks the resource pack's textures against the art contract, and draws a preview of the table.

    python resourcepack/check_art.py                        # check every file; exit code 1 if anything fails
    python resourcepack/check_art.py --preview preview.png  # the same, then draw the table top from the files

Needs: Python 3.8 or newer, nothing else (no Pillow, no pip). Works the same on Windows, macOS and Linux and
from any folder. On Windows use `py resourcepack\\check_art.py` if `python` is not found.

What is checked, per file listed in resourcepack/contract.json (the numbers of docs/ART-CONTRACT.md):
  - it exists under exactly that name (lower case), and nothing unlisted lies beside it
  - it is an RGBA PNG, 8 bits per channel, at the exact size (felt and wheel may all be at exactly half size)
  - every pixel is fully opaque or fully transparent (highlight.png and pack.png excepted)
  - wheel_base.png is opaque everywhere; the two rotors are transparent beyond the number ring
  - docs/ART-CONTRACT.md still states the same sizes, rings, pocket orders and red numbers as contract.json
Each file is reported as "placeholder" (still the generated stand-in) or "art" (somebody's own work).

The preview shows both tables (European above, American below) as the game lays them out: wheel on the left with
the rotor at rest, one ball in a pocket and one on the track, the felt on the right with a chip of each value on
a number, a line, a crossing, the street line, a dozen and RED, the win marker on 23 and the aim ring on 32.
It cannot show what only the game can: lighting, distance and movement.
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
CONTRACT = json.loads((PACK / "contract.json").read_text(encoding="utf-8"))
TEXTURES = PACK.joinpath(*CONTRACT["textures"].split("/"))
MANIFEST = PACK / "placeholders.json"
DOCUMENT = REPO / "docs" / "ART-CONTRACT.md"

FELT, WHEEL, SMALL, MENU = CONTRACT["felt"], CONTRACT["wheel"], CONTRACT["small"], CONTRACT["menu"]
RADII = WHEEL["radii"]


def expected_files():
    """path -> (name, (width, height)) for every picture of the contract; name is the file's stem."""
    files = {}
    for name in FELT["files"]:
        files[TEXTURES / f"{name}.png"] = (name, tuple(FELT["size"]))
    for name in WHEEL["files"].values():
        files[TEXTURES / f"{name}.png"] = (name, tuple(WHEEL["size"]))
    for name in SMALL["files"]:
        files[TEXTURES / f"{name}.png"] = (name, tuple(SMALL["size"]))
    for name in MENU["files"]:
        files[TEXTURES / MENU["folder"] / f"{name}.png"] = (f"{MENU['folder']}/{name}", tuple(MENU["size"]))
    files[PACK / CONTRACT["pack_icon"]["file"]] = ("pack", tuple(CONTRACT["pack_icon"]["size"]))
    return files


# ---- PNG in and out ------------------------------------------------------------------------------------------
def read_png(path):
    """(width, height, bytearray of RGBA) of an 8-bit RGBA PNG. ValueError with a fix-it message otherwise."""
    data = Path(path).read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("is not a PNG file")
    pos, idat, head = 8, [], None
    while pos + 8 <= len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            head = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            idat.append(body)
        pos += 12 + length
    if head is None:
        raise ValueError("is a damaged PNG file")
    w, h, depth, colour, _, _, interlace = head
    if (depth, colour) != (8, 6):
        kinds = {0: "greyscale", 2: "RGB without transparency", 3: "indexed (palette)", 4: "greyscale with alpha"}
        raise ValueError(f"is saved as {kinds.get(colour, 'an unusual format')}, {depth} bits; "
                         "export it as RGBA, 8 bits per channel")
    if interlace:
        raise ValueError("is interlaced; export it without interlacing")
    raw, stride = zlib.decompress(b"".join(idat)), w * 4
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
    return w, h, out


def png_bytes(w, h, rgba):
    raw = b"".join(b"\0" + bytes(rgba[y * w * 4:(y + 1) * w * 4]) for y in range(h))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


# ---- the checks ----------------------------------------------------------------------------------------------
def check_files():
    """Prints one line per file. Returns (failures, {name: (w, h, rgba)} of the files that could be read)."""
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8")) if MANIFEST.exists() else {}
    files, failures, images, scales = expected_files(), [], {}, {}

    def fail(key, text):
        failures.append(f"{key}: {text}")
        print(f"  FAIL         {key}: {text}")

    for folder in sorted({p.parent for p in files}):
        on_disk = {p.name for p in folder.iterdir()} if folder.is_dir() else set()
        wanted = {p.name for p in files if p.parent == folder}
        for extra in sorted(n for n in on_disk - wanted if n.lower().endswith(".png")):
            twin = next((n for n in wanted if n.lower() == extra.lower()), None)
            fail((folder / extra).relative_to(PACK).as_posix(),
                 f"must be named {twin} (lower case)" if twin else "is not a file of the contract (misspelt?)")
        for path in sorted(p for p in files if p.parent == folder):
            name, want = files[path]
            key = path.relative_to(PACK).as_posix()
            if path.name not in on_disk:
                fail(key, "is missing")
                continue
            try:
                w, h, px = read_png(path)
            except (ValueError, zlib.error, struct.error, IndexError) as e:
                fail(key, str(e) if isinstance(e, ValueError) else "is a damaged PNG file")
                continue
            half = name in CONTRACT["half_size_allowed"] and (w * 2, h * 2) == want
            if (w, h) != want and not half:
                fail(key, f"is {w} x {h}, must be {want[0]} x {want[1]}")
                continue
            if name in CONTRACT["half_size_allowed"]:
                scales[name] = 2 if half else 1
            alpha = px[3::4]
            if name not in SMALL["partial_alpha_allowed"] and name != "pack" \
                    and alpha.count(0) + alpha.count(255) != len(alpha):
                fail(key, "has half-transparent pixels; every pixel must be fully opaque or fully transparent")
                continue
            if name == WHEEL["files"]["base"] and alpha.count(255) != len(alpha):
                fail(key, "must be opaque everywhere, corners included")
                continue
            if name in (WHEEL["files"]["european"], WHEEL["files"]["american"]):
                c, limit = w / 2, (RADII["numbers"] + 1.5) * w / WHEEL["size"][0]
                if any(alpha[y * w + x] and math.hypot(x + 0.5 - c, y + 0.5 - c) > limit
                       for y in range(h) for x in range(w)):
                    fail(key, f"must be transparent beyond {RADII['numbers'] * w // WHEEL['size'][0]} px "
                              "from the centre")
                    continue
            images[name] = (w, h, px)
            mine = hashlib.sha256(path.read_bytes()).hexdigest() == manifest.get(key)
            print(f"  ok  {'placeholder' if mine else 'art        '}  {key}  {w} x {h}")
    if len(set(scales.values())) > 1:
        fail("felt and wheel", "mix full size and half size; all five files must use the same")
    return failures, images


def check_document():
    """The document repeats the numbers in prose; make sure it still says what contract.json says."""
    if not DOCUMENT.exists():
        return []
    doc, failures = DOCUMENT.read_text(encoding="utf-8"), []

    def need(pattern, what):
        if not re.search(pattern, doc):
            failures.append(f"docs/ART-CONTRACT.md no longer states {what} as contract.json has it")

    def size(wh):
        return rf"{wh[0]} x {wh[1]}"

    for name in FELT["files"]:
        need(rf"`{name}\.png` \| {size(FELT['size'])} \|", f"the size of {name}.png")
    for name in WHEEL["files"].values():
        need(rf"`{name}\.png` \| {size(WHEEL['size'])} \|", f"the size of {name}.png")
    need(rf"`ball\.png` \| {size(SMALL['size'])} \|", "the size of the small pictures")
    need(rf"pack\.png`, {size(CONTRACT['pack_icon']['size'])}", "the size of pack.png")
    inner = 0
    for ring, label in (("hub", "Hub"), ("cone", "Cone"), ("pockets", "Pockets"), ("numbers", "Numbers"),
                        ("track", "Ball track"), ("rim", "Rim")):
        need(rf"\| {label} \| {inner} \| {RADII[ring]} \|", f"the {label.lower()} ring")
        inner = RADII[ring]
    need(rf"centre stops at {WHEEL['ball_at_rest']}\b", "where the ball rests")
    need(rf"its centre at {WHEEL['ball_on_track']}\b", "where the ball circles")
    need(rf"One cell is {FELT['cell']} x {FELT['cell']} px", "the felt's cell size")
    for wheel, order in CONTRACT["pockets"].items():
        need("`" + " ".join(order) + "`", f"the {wheel} pocket order")
    need("Red numbers: " + ", ".join(map(str, CONTRACT["red"])) + r"\.", "the red numbers")
    for text in failures:
        print(f"  FAIL         {text}")
    return failures


# ---- the preview ---------------------------------------------------------------------------------------------
class Picture:
    def __init__(self, w, h, rgba=None):
        self.w, self.h = w, h
        self.px = rgba if rgba is not None else bytearray(b"\x30\x30\x34\xff" * (w * h))

    def scaled(self, w, h):
        """Nearest neighbour, the way the game shows a texture."""
        out = bytearray(w * h * 4)
        for y in range(h):
            sy = min(self.h - 1, int((y + 0.5) * self.h / h)) * self.w
            for x in range(w):
                s = (sy + min(self.w - 1, int((x + 0.5) * self.w / w))) * 4
                out[(y * w + x) * 4:(y * w + x) * 4 + 4] = self.px[s:s + 4]
        return Picture(w, h, out)

    def paste(self, src, left, top):
        """src over this picture, its top-left corner at (left, top), with its transparency."""
        for y in range(max(0, -top), min(src.h, self.h - top)):
            x0, x1 = max(0, -left), min(src.w, self.w - left)
            if x0 >= x1:
                continue
            row = src.px[(y * src.w + x0) * 4:(y * src.w + x1) * 4]
            o = ((y + top) * self.w + left + x0) * 4
            alpha = row[3::4]
            if alpha.count(255) == len(alpha):
                self.px[o:o + len(row)] = row
                continue
            for i, a in enumerate(alpha):
                if a == 255:
                    self.px[o + i * 4:o + i * 4 + 4] = row[i * 4:i * 4 + 4]
                elif a:
                    for c in range(3):
                        self.px[o + i * 4 + c] = (row[i * 4 + c] * a + self.px[o + i * 4 + c] * (255 - a)) // 255

    def paste_centred(self, src, cx, cy):
        self.paste(src, round(cx - src.w / 2), round(cy - src.h / 2))


def preview(images, out_path):
    cell, (felt_w, felt_h), wheel_px = FELT["cell"], FELT["size"], WHEEL["size"][0]
    origin_x = FELT["grid_origin_cells"][0] * cell
    origin_y = FELT["grid_origin_cells"][1] * cell
    missing = []

    def get(name, size=None):
        if name not in images:
            missing.append(name)
            return None
        w, h, px = images[name]
        pic = Picture(w, h, px)
        return pic.scaled(*size) if size and (w, h) != tuple(size) else pic

    def shown(name, cells):
        size = round(cells * cell)
        return get(name, (size, size))

    def spot(u, v):
        return wheel_px + origin_x + u * cell, origin_y + v * cell

    sheet = Picture(wheel_px + felt_w, felt_h * 2)
    for row, wheel in enumerate(("european", "american")):
        table = Picture(wheel_px + felt_w, felt_h)
        for name, left in ((WHEEL["files"]["base"], 0), (WHEEL["files"][wheel], 0)):
            pic = get(name, WHEEL["size"])
            if pic:
                table.paste(pic, left, 0)
        felt = get(f"felt_{wheel}", FELT["size"])
        if felt:
            table.paste(felt, wheel_px, 0)
        ball = get("ball", (WHEEL["ball_shown"], WHEEL["ball_shown"]))
        if ball:
            count = len(CONTRACT["pockets"][wheel])
            for radius, angle in ((WHEEL["ball_at_rest"], 3 * math.tau / count), (WHEEL["ball_on_track"], 5.2)):
                table.paste_centred(ball, wheel_px / 2 + radius * math.sin(angle),
                                    wheel_px / 2 - radius * math.cos(angle))
        cells = FELT["cells"]

        def centre(name):
            u0, v0, u1, v1 = cells[name]
            return (u0 + u1) / 2, (v0 + v1) / 2

        chip_spots = [centre("17"),                      # straight
                      (cells["11"][0], centre("11")[1]),  # split 8 | 11: on their shared line
                      (cells["29"][0], cells["29"][1]),   # corner 26, 27, 29, 30: on the crossing
                      (centre("13")[0], 3),               # street 13, 14, 15: on the line under the numbers
                      centre("dozen_2"),
                      centre("red")]
        for i, (u, v) in enumerate(chip_spots, 1):
            chip = shown(f"chip_{i}", SMALL["shown_cells"]["chip"])
            if chip:
                table.paste_centred(chip, *spot(u, v))
        marker = shown("marker", SMALL["shown_cells"]["marker"])
        if marker:
            table.paste_centred(marker, *spot(*centre("23")))
        ring = shown("highlight", SMALL["shown_cells"]["highlight"])
        if ring:
            table.paste_centred(ring, *spot(*centre("32")))
        sheet.paste(table, 0, row * felt_h)
    Path(out_path).write_bytes(png_bytes(sheet.w, sheet.h, sheet.px))
    print(f"preview written to {out_path} ({sheet.w} x {sheet.h}, European above, American below)")
    if missing:
        print("  left out of the preview because they failed the check: " + ", ".join(sorted(set(missing))))


def main(args):
    out = None
    if len(args) == 2 and args[0] == "--preview":
        out = args[1]
    elif args:
        print(__doc__)
        return 2
    failures, images = check_files()
    failures += check_document()
    if out:
        preview(images, out)
    if failures:
        print(f"\n{len(failures)} problem(s). The rules are in docs/ART-CONTRACT.md.")
        return 1
    print("\nAll files fit the art contract.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
