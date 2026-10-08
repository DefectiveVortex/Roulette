# CLAUDE.md

Guidance for Claude Code (and any other coding agent) working in this repository.

## Before starting any session: pull

**Several people and several agents push to this repo, so a local checkout is routinely stale. Get the latest code
before anything else**, including before reading or planning. Work based on an old `main` is wasted and conflicts.

```bash
git fetch origin && git status -sb     # how far behind or ahead you are
git pull --rebase origin main          # on main
git merge origin/main                  # on a branch you have pushed, instead of the pull
```

- If the working tree is dirty, commit or stash first; never rebase over uncommitted work.
- Never rebase a branch that is already pushed: pushing it again would need force. Merge `origin/main` into it.
  If you hold a local merge commit on `main` that is not pushed yet, use `git pull --rebase=merges origin main`.
- Pull again before every push, and again when you come back to a session that sat idle.
- If the pull changes files your task is about, re-read them before you write anything.
- Never force-push and never rewrite `main`.

## What this is

**Roulette**: a Paper server plugin that puts a playable roulette table into Minecraft. A table is built from blocks;
the felt, wheel, ball and chips are flat textures shown on display entities on top of it. Players sit down, aim at the
felt and click to place chips (a chest menu is the fallback for players without the resource pack), a timed round
closes, the wheel spins, and bets are paid in Vault money.

Two halves that must stay in step:

- `src/` — the Java plugin (Maven, Java 21, paper-api 1.21.4; one jar for Paper 1.21.4 to 26.3).
- `resourcepack/` — the client resource pack. Textures are drawn by hand; the models, item definitions and
  `pack.mcmeta` are generated.

`docs/ART-CONTRACT.md` is the interface between the two. `docs/MONEY.md` describes how stakes are kept safe.

## Who does what

- **Ankur** (GitHub `hqzzztvc`) makes the artwork and pushes it himself.
- **Vortex** (GitHub `DefectiveVortex`) and his agents write the plugin code, the generators and the docs.

Stay on your side of that line unless the person you work for says otherwise. If you find a problem on the other
side, tell your user; do not fix it silently.

## If you are working on the artwork

If the project skill `roulette-art` (`.claude/skills/roulette-art/`) is present, use it. In short:

- **All art work goes through `docs/ART-CONTRACT.md`.** It fixes every file's path, name and size, and the
  measurements the code depends on (felt cell grid, wheel centre, pocket order, ball distances). The code places chips
  and stops the ball by those numbers, not by what is painted, so a picture that ignores them looks wrong in the game
  even when it looks right in an image editor.
- Every file starts as a generated placeholder with the exact geometry. Paint over it and save under the same name.
- Check before every commit: `python3 resourcepack/generate_placeholders.py --check` (plain Python 3, nothing to
  install) must end with "all textures fit the art contract". Then look at the result, not only at the check.
- Do not edit `pack.mcmeta` or anything under `assets/roulette/models/` and `assets/roulette/items/` by hand: they
  are generated and are overwritten.
- **The contract can change, but never on one side only.** If a measurement gets in the way of a good design, raise it
  with Vortex first. An agreed change updates the document, the generator, the checker and the code geometry in one
  commit.

## If you are working on the code

- Anything that draws, places or measures against a texture takes its numbers from the art contract. Do not hard-code
  a second copy of a contract number: keep one definition and read it (the generator already reads the pocket order
  and the red numbers from the Java model).
- Never touch a texture Ankur committed. The placeholder generator only rewrites files that are still its own output.
- The outcome of a spin is drawn before the animation starts. Animation, lag or a client never decide a result.
- Money: a stake is journaled when the chip lands and is refunded exactly once if the round cannot finish. Read
  `docs/MONEY.md` before changing anything that moves money.
- Agreed for 1.0 and not to be reopened without Vortex: European and American wheels, aim-and-click betting plus the
  chest-menu fallback, Vault money only (no item stakes), one jar for Paper 1.21.4 to 26.3.

## Build and test

Needs JDK 21 or newer and Maven 3.9+. No server is needed: every test runs on plain JUnit 5.

```bash
mvn -q -B clean package            # compiles, runs the unit tests, writes target/roulette-<version>.jar
mvn -q -B -DskipTests compile      # compile check only
mvn -q -B test -Dtest=RoundTest    # one test class
```

- The jar is `target/roulette-1.0.0.jar` (the version comes from `pom.xml`, and `plugin.yml` takes it from there).
- Compiled with `--release 21` against paper-api 1.21.4; `api-version: '1.21.4'`. One jar for Paper 1.21.4 to 26.3.
- Vault is a hard dependency at runtime, PlaceholderAPI a soft one. Both are `provided` in the pom.
- Vortex's server ("berry") has no Java on the host: sessions there build with `~/Roulette-ops/bin/roulette-build`
  and follow `~/Roulette-ops/README.md`. Neither exists anywhere else.

## Layout of the code (`src/main/java/com/vortex/roulette`)

| Package | What it owns |
|---|---|
| `model/` | Pure Java, no Bukkit imports. `WheelType` (both wheels, pockets in clockwise order from 0), `Pocket` (id 37 is 00), `BetType` (payouts), `BetSpot` and `BetSpots` (every bet spot of a wheel: 157 European, 161 American; stable keys such as `straight:17`, `split:0-00`, `dozen:2`, `red`). |
| `game/` | Pure Java. `Round` is one table's state machine: IDLE → BETTING → SPINNING → RESULT → IDLE. `TableRules` holds wheel, limits and timings. `PlaceResult` says why a chip was refused. |
| `economy/` | `Bank` (the money seam), `JournaledBank` over Vault with `StakeJournal`, `Money` (installs it at enable), `TableLimits`. See `docs/MONEY.md`. |
| `stats/` | `StatsManager`, `/roulette stats` and `top`, PlaceholderAPI expansion. |
| `display/` | `SpinCurve` (rotor and ball positions that end on the drawn pocket), `WheelView`, `NumberBoard`. |
| `pack/` | `PackDelivery`: offers the resource pack by URL, knows who has it. |
| `config/` | `ConfigManager` (settings and messages), `ConfigFileUpdater` and `ConfigValidator` (repair and check `config.yml` and `messages*.yml` on every load), `ConfigMigrations`. |
| `update/` | Modrinth update checker. Off until `UpdateChecker.PROJECT_ID` and `PROJECT_SLUG` are filled in. |
| `command/` | `/roulette`: help, version, reload, update; routes `stats` and `top` to `stats/`. |
| `util/` | `SafeYaml`: atomic writes, moving an unreadable file aside. |
| `table/`, `model/layout/`, `input/`, `gui/` | Tables and seating, felt geometry, aim-and-click input, the chest menu. Being written (2026-10-08): look before relying on this row. |

`RoulettePlugin` is the composition root: it builds the config, the bank, statistics, pack delivery and the update
service, hands out rounds with `newRound(tableId, rules)`, and on disable aborts every round before closing the
journal.

## The seams

- **Time, money and chance are injected into `Round`**: `GameClock` (server ticks), `Bank`, `PocketSource`
  (`SecureRandom` in production). Tests drive a round with `ManualClock`, `FakeBank` and a scripted pocket
  (`src/test/java/.../game`).
- **`RoundListener`** is how a round talks to everything else, in order: `bettingOpened`, `betPlaced` / `betRemoved`,
  `betsClosed`, `spinStarted(result, durationTicks)`, `resultSettled`, `cleared`. Views, statistics and messages all
  implement it. A listener that throws is logged and skipped; it cannot affect money.
- **`Bank`**: `reserve` takes a stake when a chip lands, `refund` gives back what is still reserved, `pay` settles one
  player after the result (called once per bettor, losers included). Every stake ends in exactly one refund or pay.
- **The result is drawn in `Round` when betting closes**, before `spinStarted`. The animation receives it and has to
  end on it; the payout fires after `spinTicks` whether or not the animation kept up.
- **Limits are enforced in `Round.place`** from `TableRules` (minimum and maximum per spot per player, maximum win on
  one result).

## Rules that are easy to break

- `WheelType.EUROPEAN("...")` / `AMERICAN("...")` and `Pocket.RED = Set.of(...)` are read by
  `resourcepack/generate_placeholders.py` with regexes. Keep their shape or update the generator in the same commit.
- A new setting goes into `src/main/resources/config.yml` with a comment and, if it has a range or format, into
  `ConfigValidator.configRanges()`. A new message goes into `messages.yml`; code reads it with
  `plugin.config().message(key, "name", value...)`.
- Renaming or moving a config key needs a migration in `ConfigMigrations` and a bump of
  `ConfigFileUpdater.CURRENT_VERSION`, otherwise an admin's value is lost on update.

## Releases

Nothing is published (GitHub release, Modrinth) without Vortex's explicit approval of that release.
