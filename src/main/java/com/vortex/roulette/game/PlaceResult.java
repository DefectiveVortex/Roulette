package com.vortex.roulette.game;

/** Outcome of trying to place or take back a chip. Only {@link #OK} changed anything. */
public enum PlaceResult {
    OK,
    /** Betting is closed (spinning or showing the result). */
    CLOSED,
    /** The spot does not exist on this table's wheel. */
    INVALID_SPOT,
    /** Amount is zero or negative. */
    INVALID_AMOUNT,
    /** The player's stake on this spot would be under the table minimum. */
    BELOW_MIN,
    /** The player's stake on this spot would exceed the table maximum. */
    ABOVE_MAX,
    /** The player could win more than the table's maximum payout on one result. */
    ABOVE_MAX_PAYOUT,
    /** The bank could not take the stake. */
    INSUFFICIENT_FUNDS,
    /** Taking back: the player has no chips on that spot. */
    NO_BET
}
