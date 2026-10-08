# Money: the Vault bank and the stake journal

How `economy/` keeps the promise that a stake is refunded or paid **exactly once**, whatever happens to the server.
Owner: Roulette 4. The seam is `economy.Bank` (`reserve`, `refund`, `pay`, `balance`); the round never talks to Vault.

## The rule

A stake leaves the player's balance when the chip lands (`reserve`). From then on the plugin *holds* it for one
`(roundId, player)` until exactly one thing accounts for it:

| What happens | Call | Journal line |
|---|---|---|
| chip taken back, player leaves during betting, `/stop`, disable, table removed | `refund` | `REFUND` |
| result is in (winners and losers) | `pay` | `PAY` |
| the server died first (crash, `kill -9`, power cut) | recovery at the next start | `VOID` |

Money that could not be handed over (the economy refused the deposit) is not dropped: it becomes **owed** and is
retried when the economy is back and when the player next joins.

## The journal

`plugins/Roulette/stakes.journal`, append-only text, one line per event, written on the server thread:

```
# roulette stake journal v1
<epochMillis> RESERVE <roundId> <player> <amount>            after the withdrawal succeeded
<epochMillis> REFUND  <roundId> <player> <amount>            before the deposit
<epochMillis> PAY     <roundId> <player> <staked> <payout>   before the deposit; staked is consumed
<epochMillis> VOID    <roundId> <player> <amount>            recovery: a dead round's stake becomes owed
<epochMillis> OWE     <player> <amount>                      a deposit was refused; still owed
<epochMillis> DELIVER <player> <amount>                      before the deposit that settles what was owed
```

Replaying the file gives two maps and nothing else: `held[roundId][player]` and `owed[player]`. Memory is only ever a
replay of the file, so there is no second source of truth to drift.

**Order of the two steps.** The journal line and the Vault call cannot be made atomic, so the order is chosen so that
a crash between them can lose money but never create it (the same rule as Uno's escrow):

- taking: withdraw, then write `RESERVE`. A crash in between loses that one chip's stake.
- giving: write `REFUND` / `PAY` / `DELIVER`, then deposit. A crash in between loses that one deposit.

The gap is two adjacent statements. Everything else is covered: the line is in the operating system's page cache as
soon as it is written, so a JVM crash or `kill -9` cannot lose it.

**Refund once.** `refund` and `pay` look at what the journal holds, not at what the caller says: a refund is capped at
the amount still held, and a `pay` for a player with nothing held pays nothing. A second `abort()`, a listener that
calls twice, or recovery running after a clean refund all find `held = 0` and do nothing.

**Recovery (every enable).** Replay the file. Every round id in it is dead (round ids are random per cycle), so each
remaining `held` entry is turned into `owed` with a `VOID` line. One tick later, when every plugin has enabled and the
economy is registered, everything owed is delivered: `DELIVER`, then deposit, to offline players too. If the deposit
is refused the amount goes back to `owed` (`OWE`) and is tried again when that player joins. A round the server died
in *never happened*: all its stakes come back, also when the ball was already rolling. Nobody can tell which pocket
it would have been, and nobody but an admin can stop the server.

**Disk.** A line is about 95 bytes. Writes go to an open `FileChannel` (microseconds); a background thread calls
`force` at most five times a second while there is something new, so the server thread never waits for the SD card
and a power cut loses at most the last 200 ms of events. Nothing is written while nobody bets. When no round is open
and the file is over 1 MiB it is rotated: `stakes.journal` becomes `stakes.journal.1` (one generation is kept as the
audit trail of who was paid what) and a new file starts with the `OWE` lines still outstanding.

**If the journal cannot be written** (disk full, read-only): the stake that was just withdrawn is deposited straight
back, `reserve` returns false, and every further `reserve` is refused until a write succeeds again. Refunds and
payouts still go out. A table that cannot record stakes takes none.

**A damaged file.** A torn last line (power cut) and lines that do not parse are skipped with a warning; the rest is
replayed. Amounts must be positive; a line that would make `held` negative is clamped at zero.

## What it cannot do

- Vault has no transactions. If the economy plugin itself loses its last writes in a power cut while the journal kept
  its lines, a refunded stake was never really taken. No plugin can close that gap from outside the economy.
- A deposit that throws is treated as refused and retried, on the assumption that an economy throws before it
  changes the balance.
- Payouts are created and stakes destroyed: there is no house account in 1.0 (idea in `Roulette-ops/NOTES.md`).

## Limits

`game.TableRules` carries the limits and `Round.place` enforces them: minimum and maximum a player may have on one
spot, and the most a player may win on a single result (`maxPayout`, 0 = no cap; a chip that would break it is
refused when it lands, never trimmed at payout). `economy.TableLimits` supplies the defaults and repairs bad values
from the config.

## Test

Unit tests replay every crash point against a fake economy (`StakeJournalTest`, `VaultBankTest`). On the test server
the bots check balances before and after: win, lose, take back, leave, `/stop` mid-round, `kill -9` during betting
and during the spin, restart twice (the second start must pay nothing), and a refused deposit.
