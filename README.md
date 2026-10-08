# Roulette

A Paper plugin that puts a playable roulette table into Minecraft. A table is built from blocks; the felt, the wheel,
the ball and the chips are flat textures shown on top of it. Players sit down, aim at the felt and click to place
chips, a timed round closes, the wheel spins, and bets are paid in Vault money.

**Status: in development, not released yet.** The resource pack in this repository holds generated placeholder art;
the real artwork is still being drawn.

## What it does

- **European and American wheels.** Each table is one or the other: European has a single zero (37 pockets), American
  has 0 and 00 (38 pockets). European is the default.
- **Shared, timed rounds.** The first chip opens betting with a countdown. Then "no more bets", the wheel spins, the
  bets are paid and the table clears. Every seated player bets in the same round.
- **Two ways to bet.**
  - *Aiming:* look at the felt and right-click to put the selected chip on the bet you aim at; left-click takes one
    back. The mouse wheel changes the chip. Every bet in the table below can be placed this way.
  - *Menu:* press F (the swap-hands key) while seated (or `/roulette menu`) for a chest menu with every number and every outside bet.
    Left-click places a chip, right-click takes one back. It works without the resource pack. Splits, streets,
    corners and lines are only on the felt.
- **Fair results.** The winning number is drawn with `SecureRandom` when betting closes, before the wheel starts to
  turn. The animation only shows it: lag or a client cannot change a result.
- **Safe money.** A stake leaves the player's balance when the chip lands and is recorded in a journal. If the round
  cannot finish (the player leaves during betting, `/stop`, a crash, the table is removed), the stake is refunded
  exactly once. See [docs/MONEY.md](docs/MONEY.md).
- **Statistics** per player and leaderboards, with PlaceholderAPI placeholders.

## Bets and payouts

A winning bet pays the amount shown and returns the stake.

| Bet | Covers | Pays |
|---|---|---|
| Straight | one number | 35 to 1 |
| Split | two neighbouring numbers | 17 to 1 |
| Street | a row of three numbers | 11 to 1 |
| Trio | three numbers including a zero (0-1-2, 0-2-3; American: 0-1-2, 0-00-2, 00-2-3) | 11 to 1 |
| Corner | four numbers that share a corner | 8 to 1 |
| First four | 0-1-2-3, European wheel only | 8 to 1 |
| Top line | 0-00-1-2-3, American wheel only | 6 to 1 |
| Six line | two neighbouring rows | 5 to 1 |
| Dozen, column | twelve numbers | 2 to 1 |
| Red, black, odd, even, low (1-18), high (19-36) | eighteen numbers | 1 to 1 |

There is no La Partage, En Prison or call bet.

## Requirements

- Paper 1.21.4 to 26.3 (one jar for all of them), Java 21 or newer.
- [Vault](https://www.spigotmc.org/resources/vault.34315/) and an economy plugin that registers with it. Without an
  economy no bet can be placed.
- Optional: PlaceholderAPI.

## Install

1. Put the Roulette jar, Vault and your economy plugin into `plugins/` and start the server.
2. Host the resource pack zip somewhere players can download it and set `resource-pack.url` (and `sha1`) in
   `plugins/Roulette/config.yml`. The plugin does not host the file. The pack is added on top of your server
   resource pack; it does not replace it.
3. Stand where the table should be and run `/roulette create <name>` (add `american` for an American wheel). The
   table is 9 x 3 blocks with seven stools around it.
4. Right-click the table or a stool to sit down. Sneak or `/roulette leave` to get up.

To make the pack zip from this repository: `python3 resourcepack/generate_placeholders.py --zip` writes
`target/Roulette-Textures.zip`.

## Commands

| Command | What it does | Permission |
|---|---|---|
| `/roulette help` | The command list | none |
| `/roulette version` | Plugin version | none |
| `/roulette menu` | The bet menu, while seated | `roulette.play` |
| `/roulette leave` | Leave your table | `roulette.play` |
| `/roulette stats [player]` | Statistics, your own or another player's | `roulette.play`; another player needs `roulette.stats.others` |
| `/roulette top [wagered\|won\|net\|biggest\|rounds]` | The leaderboards | `roulette.play` |
| `/roulette create <name> [european\|american]` | Build a table in front of you | `roulette.admin` |
| `/roulette remove <name>` | Remove a table | `roulette.admin` |
| `/roulette list` | All tables | `roulette.admin` |
| `/roulette set <name> <setting> <value>` | Change one table's wheel, limits or timings | `roulette.admin` |
| `/roulette reload` | Reload config and messages | `roulette.admin` |
| `/roulette update` | Check for a new version now | `roulette.admin` |

Settings for `/roulette set`: `wheel`, `min-bet`, `max-bet`, `max-payout`, `betting-seconds`, `spin-seconds`,
`result-seconds`.

## Permissions

| Permission | Default | Allows |
|---|---|---|
| `roulette.play` | everyone | Sitting at tables and betting, own statistics, leaderboards |
| `roulette.stats.others` | op | Other players' statistics |
| `roulette.admin` | op | Creating, removing and changing tables, reload, update check. Includes the two above |

## Configuration

`plugins/Roulette/config.yml`; apply changes with `/roulette reload`. The file is checked on every start and reload:
missing settings are added, and a value that makes no sense is reported in the console and replaced by its default.

| Key | Default | Meaning |
|---|---|---|
| `language` | `en` | Messages are read from `messages.yml`, other languages from `messages_<code>.yml` |
| `table.wheel` | `european` | Wheel of a new table: `european` or `american` |
| `table.min-bet`, `table.max-bet` | `10`, `1000` | Smallest and largest stake a player may have on one bet spot |
| `table.max-payout` | `50000` | The most one player can win on one spin; a bet that could win more is refused. `0` = no limit |
| `table.betting-seconds` | `30` | From the first chip to "no more bets" |
| `table.spin-seconds` | `8` | How long the wheel spins |
| `table.result-seconds` | `5` | How long the winning number stays before the table clears |
| `wheel.board-numbers` | `10` | How many past numbers the board above the wheel shows; `0` = no board |
| `wheel.sounds.track`, `.pocket`, `.rest` | button clicks | Sounds of the ball, as `<sound> <volume> <pitch>`; empty = silent |
| `resource-pack.send` | `table` | When the pack is offered: `table` (first time a player sits down), `join` or `never` |
| `resource-pack.url`, `.sha1` | empty | Link to the pack zip and its SHA-1 |
| `resource-pack.required` | `false` | `true` kicks players who decline the pack |
| `updates.check`, `.auto-download`, `.channel`, `.interval-hours` | `true`, `true`, `release`, `12` | Looking for new versions on Modrinth, once Roulette is published there |
| `stats.save-minutes` | `5` | How often changed statistics are written to disk |
| `chips.values` | `10, 50, 100, 250, 500, 1000` | What the six chips are worth: six rising whole amounts |
| `build.table-block`, `build.stool-block` | `GREEN_TERRACOTTA`, `DARK_OAK_STAIRS` | What a new table is built from |

The `table.*` values are the defaults for new tables. Each table keeps its own copy, changed with `/roulette set`.
The house pays every win, so `max-bet` and `max-payout` are the server's risk limit.

Every message players see is in `messages.yml`. Tables are stored in `tables.yml`, statistics in `stats.yml` and the
open stakes in `stakes.journal`, all in `plugins/Roulette/`.

## PlaceholderAPI

| Placeholder | Value |
|---|---|
| `%roulette_rounds%`, `%roulette_wins%`, `%roulette_win_rate%` | The player's rounds, winning rounds and win rate in percent |
| `%roulette_wagered%`, `%roulette_won%`, `%roulette_net%`, `%roulette_biggest_win%` | The player's money figures as plain numbers; add `_formatted` for money format |
| `%roulette_top_<board>_<1-10>_name%`, `..._value%`, `..._value_formatted%` | Leaderboard entries; `<board>` is `wagered`, `won`, `net`, `biggest` or `rounds` |
| `%roulette_last_number%`, `%roulette_hot_number%`, `%roulette_spins%` | The last number, the most frequent number and the number of spins, over every table |
| `%roulette_hits_<number>%` | How often a number (0 to 36, or 00) came up |

## Building from source

Needs JDK 21 or newer and Maven 3.9 or newer. The tests run on plain JUnit 5; no server is needed.

```bash
mvn -q -B clean package      # compiles, runs the tests, writes target/roulette-<version>.jar
```

## Artwork

The textures are specified in [docs/ART-CONTRACT.md](docs/ART-CONTRACT.md): every file's path and size, and the
measurements the plugin relies on. `python3 resourcepack/check_art.py` checks the pack against it.

## Credits and licence

- Code: [DefectiveVortex](https://github.com/DefectiveVortex)
- Artwork: auknr (in progress; the pack currently holds generated placeholder art)

Licensed under the [GNU General Public License v3.0](LICENSE).
