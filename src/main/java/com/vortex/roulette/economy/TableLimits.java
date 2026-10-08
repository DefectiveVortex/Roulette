package com.vortex.roulette.economy;

import com.vortex.roulette.model.BetType;
import java.util.function.Consumer;

/**
 * A table's money limits, in whole currency units, as {@code game.TableRules} wants them. The house pays every win,
 * so these are what bounds its risk.
 *
 * @param minBet    smallest total a player may have on one spot
 * @param maxBet    largest total a player may have on one spot
 * @param maxPayout most a player may win (stakes not counted) on a single result; 0 = no limit
 */
public record TableLimits(long minBet, long maxBet, long maxPayout) {
    /** Above this a full table's payout stops being exact in the doubles Vault counts in. */
    public static final long HIGHEST_BET = 1_000_000_000_000L;
    /** A straight-up bet pays 35 to 1: the largest odds on the table. */
    private static final long HIGHEST_ODDS = BetType.STRAIGHT.payout();

    public static final TableLimits DEFAULT = new TableLimits(1, 1000, 0);

    public TableLimits {
        if (minBet < 1 || maxBet < minBet || maxBet > HIGHEST_BET || maxPayout < 0) {
            throw new IllegalArgumentException("limits: min " + minBet + ", max " + maxBet + ", payout " + maxPayout);
        }
    }

    /**
     * Limits from values an admin typed, repaired where they make no sense. Every repair is reported through
     * {@code warn} in words that name the setting.
     */
    public static TableLimits of(long minBet, long maxBet, long maxPayout, Consumer<String> warn) {
        if (minBet < 1) {
            warn.accept("min-bet " + minBet + " is below 1; using 1.");
            minBet = 1;
        }
        if (minBet > HIGHEST_BET) {
            warn.accept("min-bet " + minBet + " is above the highest possible bet; using " + HIGHEST_BET + ".");
            minBet = HIGHEST_BET;
        }
        if (maxBet < minBet) {
            warn.accept("max-bet " + maxBet + " is below min-bet " + minBet + "; using " + minBet + ".");
            maxBet = minBet;
        }
        if (maxBet > HIGHEST_BET) {
            warn.accept("max-bet " + maxBet + " is above the highest possible bet; using " + HIGHEST_BET + ".");
            maxBet = HIGHEST_BET;
        }
        if (maxPayout < 0) {
            warn.accept("max-payout " + maxPayout + " is negative; using 0 (no limit).");
            maxPayout = 0;
        }
        if (maxPayout > 0 && maxPayout < minBet) {
            // Even an even-money bet at the minimum would win more than this: nobody could bet at all.
            long playable = minBet * HIGHEST_ODDS;
            warn.accept("max-payout " + maxPayout + " is below min-bet " + minBet + ", so no bet could be placed; using "
                    + playable + ".");
            maxPayout = playable;
        } else if (maxPayout > 0 && maxPayout < minBet * HIGHEST_ODDS) {
            warn.accept("max-payout " + maxPayout + " is below " + (minBet * HIGHEST_ODDS) + " (a straight-up bet at"
                    + " min-bet " + minBet + " wins that much), so some inside bets will always be refused.");
        }
        return new TableLimits(minBet, maxBet, maxPayout);
    }

    /** The most a single bet can win under these limits: a straight-up at the maximum, or the cap if it is lower. */
    public long highestSingleWin() {
        long straight = maxBet * HIGHEST_ODDS;
        return maxPayout > 0 ? Math.min(straight, maxPayout) : straight;
    }
}
