# Art contract

What the Roulette plugin needs drawn, where each file goes, and the few measurements the code depends on.
Everything not listed under "The code relies on" is yours to design.

Status 2026-10-08: the geometry below is final. A generated placeholder for every file is being added to the repo at
the same paths; they have the exact geometry, so you can paint over them. Until they are there, this document is
enough to start.

## How it is shown in the game

The table is built from ordinary blocks, 9 long and 3 wide. Your textures are laid flat on top of it, like stickers:

```
      3 blocks                 6 blocks
 +--------------+------------------------------------+
 |              |                                    |
 |    wheel     |               felt                 |  3 blocks
 |              |  0 | 3 6 9 ...               36 |  |
 +--------------+------------------------------------+
```

- No 3D models. Every file is a flat picture seen from above.
- The game adds no lighting or shading to them. Any shadow, bevel or highlight has to be painted in.
- The wheel is three layers on top of each other: a bowl that stays still, a numbered rotor that turns, and the ball.
- Felt and wheel have the same pixel density (about 170 px per block), and the felt's left edge touches the wheel
  picture's right edge. Paint the cloth so the two meet without a visible step.

## Files

All textures go in `resourcepack/assets/roulette/textures/item/`. File names are lower case, exactly as written.

| File | Size (px) | What it is |
|---|---|---|
| `felt_european.png` | 1024 x 512 | Betting layout with a single zero |
| `felt_american.png` | 1024 x 512 | Betting layout with 0 and 00 |
| `wheel_base.png` | 512 x 512 | The bowl: rim, ball track and the table surface around it. Does not turn. Used for both wheels |
| `wheel_european.png` | 512 x 512 | The rotor with 37 pockets. Turns |
| `wheel_american.png` | 512 x 512 | The rotor with 38 pockets. Turns |
| `ball.png` | 32 x 32 | The ball, seen from above, filling the picture |
| `chip_1.png` ... `chip_6.png` | 32 x 32 | Six chips, lowest value to highest, seen from above |
| `marker.png` | 32 x 32 | The win marker (dolly) put on the winning number |
| `highlight.png` | 32 x 32 | A ring shown on the bet spot a player is aiming at |
| `menu/*.png` (optional) | 32 x 32 | Icons for the chest menu, see "Menu icons" |

And one file outside that folder: `resourcepack/pack.png`, 256 x 256, the pack's icon in the resource pack list.

Rules for every file:

- PNG with transparency (RGBA, 8 bits per channel), at exactly the size given.
- Each pixel is either fully opaque or fully transparent. Half-transparent pixels can show as holes or as solid
  colour depending on the player's game version. The one exception is `highlight.png`, where they are allowed.
- If you would rather work with bigger pixels, exactly half size is fine (felt 512 x 256, wheel 256 x 256), as long
  as both felts and all three wheel files use the same choice. Halve every number below.

## Felt

Both felt files use the same grid. Only the zero column differs. The picture is drawn like a normal layout diagram:
zero on the left, the wheel is beyond the left edge.

One cell is 64 x 64 px. Coordinates are in pixels from the top-left corner of the picture, x to the right, y down.

```
 x:  64  128  192  256  320  384  448  512  576  640  704  768  832  896  960
 y
  96  +----+----+----+----+----+----+----+----+----+----+----+----+----+----+
      |    |  3 |  6 |  9 | 12 | 15 | 18 | 21 | 24 | 27 | 30 | 33 | 36 |2to1|
 160  |    +----+----+----+----+----+----+----+----+----+----+----+----+----+
      |  0 |  2 |  5 |  8 | 11 | 14 | 17 | 20 | 23 | 26 | 29 | 32 | 35 |2to1|
 224  |    +----+----+----+----+----+----+----+----+----+----+----+----+----+
      |    |  1 |  4 |  7 | 10 | 13 | 16 | 19 | 22 | 25 | 28 | 31 | 34 |2to1|
 288  +----+----+----+----+----+----+----+----+----+----+----+----+----+----+
           |      1st 12       |      2nd 12       |      3rd 12       |
 352       +---------+---------+---------+---------+---------+---------+
           |  1-18   |  EVEN   |   RED   |  BLACK  |   ODD   |  19-36  |
 416       +---------+---------+---------+---------+---------+---------+
```

| Area | x | y |
|---|---|---|
| European `0` | 64 to 128 | 96 to 288 (one tall cell) |
| American `00` | 64 to 128 | 96 to 192 (top half, beside 3) |
| American `0` | 64 to 128 | 192 to 288 (bottom half, beside 1) |
| Numbers 1 to 36 | 128 to 896, 64 px per column | top row 96 to 160: 3, 6 ... 36; middle row 160 to 224: 2, 5 ... 35; bottom row 224 to 288: 1, 4 ... 34 |
| Column bets ("2 to 1") | 896 to 960 | three cells: 96 to 160, 160 to 224, 224 to 288 |
| Dozens | 128 to 384, 384 to 640, 640 to 896 | 288 to 352 |
| Even-money bets | six cells of 128 px from x = 128: 1-18, EVEN, RED, BLACK, ODD, 19-36 | 352 to 416 |

**The code relies on:**

- The cell boundaries in the table above. A click is matched to a cell by these numbers, not by what is painted.
- Grid lines centred on the boundaries, at most 4 px wide. Chips for bets on two or more numbers sit on the lines
  and on the line crossings (for example a chip on the line between 8 and 11, or on the crossing of 8, 9, 11, 12),
  and chips for a column of three numbers sit on the y = 288 line. Keep the lines and crossings clear of ornaments.
- Which number is in which cell, and the order of the outside bets.

Yours to decide: colours, lettering, which way the numbers face, the shape drawn inside the zero cells, how RED
and BLACK are shown (word or diamond), and everything outside the grid. That is the border (x under 64 or over
960, y under 96 or over 416) and the two empty corners beside the dozens and even-money rows. The picture covers
the whole 6 x 3 block table top, so paint the cloth across the full canvas. Chips are about 29 px across on this
picture.

Red numbers: 1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36. The other numbers from 1 to 36 are
black. 0 and 00 are green.

## Wheel

All three wheel files are 512 x 512 with the wheel's centre at the exact middle of the picture: the point where
the four middle pixels meet, x = 256, y = 256. Distances below are in pixels from that centre.

| Ring | From | To | File | Notes |
|---|---|---|---|---|
| Hub | 0 | 44 | rotor | Decoration (turret, handle) |
| Cone | 44 | 132 | rotor | Decoration |
| Pockets | 132 | 176 | rotor | Where the ball comes to rest. The ball's centre stops at 154 |
| Numbers | 176 | 208 | rotor | The number of each pocket on its red, black or green field |
| Ball track | 208 | 232 | base | The ball circles here, its centre at 220 |
| Rim | 232 | 240 | base | Outer edge of the bowl |
| Surround | 240 | corners | base | Table surface around the bowl |

`wheel_base.png` is opaque everywhere, corners included. Paint it inward to at least 200 so nothing shows through
at the seam with the rotor. What is under the rotor is never seen.

`wheel_european.png` and `wheel_american.png` are transparent beyond 208.

**The code relies on:**

- The centre at (256, 256), and the two ball distances (220 on the track, 154 at rest).
- The `0` pocket centred at 12 o'clock, straight up from the centre.
- Pockets of equal width, in the order below going clockwise. Pocket number k in the list (0 for the first) is
  centred k x 360 / 37 degrees clockwise from 12 o'clock on the European wheel, and k x 360 / 38 degrees on the
  American wheel. The dividers between pockets are halfway between two pocket centres.
- The same red, black and green numbers as on the felt.

European, clockwise from the top:
`0 32 15 19 4 21 2 25 17 34 6 27 13 36 11 30 8 23 10 5 24 16 33 1 20 14 31 9 22 18 29 7 28 12 35 3 26`

American, clockwise from the top:
`0 28 9 26 30 11 7 20 32 17 5 22 34 15 3 24 36 13 1 00 27 10 25 29 12 8 19 31 18 6 21 33 16 4 23 35 14 2`

On the American wheel `00` is exactly at 6 o'clock.

One pocket is about 33 px wide at the number ring and about 26 px wide where the ball rests. Turning 37 numbers
by hand is tedious: the placeholder rotors have every number already placed and turned, so painting over them is
the quick way.

## Ball, chips, marker, highlight

- **Ball.** Draw it filling the 32 x 32 picture. It is shown small, about 20 px across on the wheel picture, so a
  plain bright ball with one highlight reads best.
- **Chips.** `chip_1` is the lowest value, `chip_6` the highest. No numbers on them: server owners set the values.
  Six colours that are easy to tell apart on green cloth and on red and black cells. The same picture is the icon
  in the player's hotbar and the chip lying on the felt. Players without the pack see dyes instead, in the order
  white, red, blue, green, black, purple; keeping that order is a suggestion, not a rule.
- **Marker.** Shown on the winning number, about half a cell across. A ring or a small dolly seen from above works
  better than a solid disc, because the number under it should stay readable.
- **Highlight.** A ring about the size of a chip, shown where the player is aiming. It may be half-transparent.

## Menu icons (optional)

Players without the resource pack bet through a chest menu, which uses ordinary Minecraft items. Players with the
pack can get proper icons there. Lowest priority: the generated ones are usable as they are.

In `resourcepack/assets/roulette/textures/item/menu/`, each 32 x 32: `n_0.png` to `n_36.png`, `n_00.png`,
`dozen_1.png` to `dozen_3.png`, `column_1.png` to `column_3.png`, `red.png`, `black.png`, `odd.png`, `even.png`,
`low.png` (1-18), `high.png` (19-36). They are shown at inventory-slot size, so keep them bold.

## Not needed

The table itself (blocks), anything 3D, sounds, fonts, and the board of recent numbers (plain game text).

## Working in the repo

- Replace a placeholder by saving your file over it under the same name, then commit and push as usual. Run
  `git pull --rebase origin main` first, because the code sessions push to `main` too.
- `resourcepack/generate_placeholders.py` makes the placeholders. It never overwrites a file you have changed: it
  only refreshes files that are still byte-for-byte its own output.
- Leave `pack.mcmeta` and everything under `assets/roulette/models/` and `assets/roulette/items/` alone. They are
  generated and tell the game how to lay the pictures flat.
- Nobody on the code side can see the game. Vortex checks on the test server whether things line up. If a
  measurement here gets in the way of a good design, say so through him before drawing around it.
