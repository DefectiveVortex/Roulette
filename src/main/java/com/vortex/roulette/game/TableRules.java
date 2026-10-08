package com.vortex.roulette.game;

import com.vortex.roulette.model.WheelType;

/**
 * Everything about a table the round needs. Amounts are whole currency units.
 *
 * @param minBet       smallest total a player may have on one spot
 * @param maxBet       largest total a player may have on one spot
 * @param maxPayout    most a player may win (stakes not counted) on a single result; 0 = no limit
 * @param bettingTicks countdown from the first chip to "no more bets"
 * @param spinTicks    how long the wheel animation runs before the payout
 * @param resultTicks  how long the result stays on the table before it clears
 */
public record TableRules(WheelType wheel, long minBet, long maxBet, long maxPayout,
                         int bettingTicks, int spinTicks, int resultTicks) {

    public TableRules {
        if (wheel == null) {
            throw new IllegalArgumentException("wheel");
        }
        if (minBet < 1 || maxBet < minBet || maxPayout < 0) {
            throw new IllegalArgumentException("limits: min " + minBet + ", max " + maxBet + ", payout " + maxPayout);
        }
        if (bettingTicks < 1 || spinTicks < 1 || resultTicks < 0) {
            throw new IllegalArgumentException("timings");
        }
    }

    /** European wheel, 1 to 1000 per spot, no payout cap, 30 s betting, 8 s spin, 5 s result. */
    public static TableRules defaults() {
        return new TableRules(WheelType.EUROPEAN, 1, 1000, 0, 600, 160, 100);
    }
}
