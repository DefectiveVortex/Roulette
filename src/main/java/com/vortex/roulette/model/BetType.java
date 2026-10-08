package com.vortex.roulette.model;

/** Every kind of bet in 1.0 with its payout as "{@code payout} to 1" (the stake is returned on top). */
public enum BetType {
    STRAIGHT(35, true),
    SPLIT(17, true),
    STREET(11, true),
    /** Three numbers including a zero: 0-1-2 and 0-2-3 (European); 0-1-2, 0-00-2 and 00-2-3 (American). */
    TRIO(11, true),
    CORNER(8, true),
    /** 0-1-2-3, European wheel only. */
    FIRST_FOUR(8, true),
    /** 0-00-1-2-3, American wheel only. */
    TOP_LINE(6, true),
    SIX_LINE(5, true),
    DOZEN(2, false),
    COLUMN(2, false),
    RED(1, false),
    BLACK(1, false),
    ODD(1, false),
    EVEN(1, false),
    LOW(1, false),
    HIGH(1, false);

    private final int payout;
    private final boolean inside;

    BetType(int payout, boolean inside) {
        this.payout = payout;
        this.inside = inside;
    }

    /** Winnings per unit staked; a winning bet returns {@code stake * (payout + 1)}. */
    public int payout() {
        return payout;
    }

    /** Inside bets sit on the number grid; outside bets are the boxes around it. */
    public boolean isInside() {
        return inside;
    }
}
