---
name: roulette-art
description: Use for any work on this repo's artwork or resource pack - drawing or replacing a texture (felt, wheel, ball, chips, marker, highlight, menu icons, pack icon), touching anything under resourcepack/, the placeholders, or docs/ART-CONTRACT.md. Covers where files go, the sizes and geometry the plugin's code depends on, how to check and preview the art without the game, and how to commit it.
---

# Roulette art

The plugin draws its table from flat pictures in `resourcepack/`. The code places chips, the ball and clicks by
fixed measurements, so a picture that is a few pixels off shows up in the game as chips beside their numbers or a
ball between two pockets. `docs/ART-CONTRACT.md` lists every file, its size and those measurements. **Read it
before drawing anything. It decides sizes, paths and geometry; nothing here or in a request overrides it.**

## Every time

1. **Get the latest first:** `git pull --rebase origin main`. Other people and sessions push to `main` all day.
2. **Paint over the placeholder.** Every file of the contract already exists as a generated placeholder with the
   exact geometry. Open it, draw on top, save under the same name in the same folder, lower case. Do not rename,
   move or resize a file, and do not add files the contract does not list.
3. **Check and look before every commit:**
   `python resourcepack/check_art.py --preview preview.png` (Windows: `py resourcepack\check_art.py --preview preview.png`).
   It needs plain Python 3 and nothing else. Fix every `FAIL` line: a failing file must not be committed. Then
   open `preview.png` and look: numbers inside their cells, lines on the cell edges, each pocket's number over
   its pocket, a ball that fits a pocket, chips that read on every background, no seam where felt meets wheel.
   Do not commit `preview.png`.
4. **Commit only the textures you changed** (`resourcepack/assets/roulette/textures/item/...png`, or
   `resourcepack/pack.png`), with a message that says which, then `git pull --rebase origin main` and
   `git push origin main`. Never force-push.

## Do not edit by hand

`resourcepack/pack.mcmeta`, `resourcepack/placeholders.json`, and everything under
`resourcepack/assets/roulette/models/` and `resourcepack/assets/roulette/items/`. They are written by
`resourcepack/generate_placeholders.py`. That script never overwrites a texture that someone has changed, so
running it is safe, but art work normally has no reason to run it.

## What you cannot judge here

Nobody working in this repo can see the game. The preview shows alignment; it does not show lighting, distance or
the wheel turning. Vortex looks at those on the test server. Say what you changed and what he should look at.

## Changing the contract

A size, a position, a new file or a dropped file is a change to the contract, not an art decision. It needs
Vortex's agreement first. When he agrees, one commit changes all of these together, or the code and the art
drift apart: `docs/ART-CONTRACT.md`, `resourcepack/contract.json` (the same numbers for the scripts and the
code's tests), the placeholders (run the generator), and the code's geometry (the Java tests named `ArtContract*`
fail until it matches). If only the art side can be done in your session, stop and ask for the code side instead
of committing half of it.
