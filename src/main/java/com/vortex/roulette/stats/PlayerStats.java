package com.vortex.roulette.stats;

/** One player's lifetime roulette numbers, in whole currency units. A round is one spin the player had chips on. */
public final class PlayerStats {
    String name;
    long rounds;
    long wins;
    long wagered;
    long paid;
    long biggestWin;

    PlayerStats(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public long rounds() {
        return rounds;
    }

    /** Rounds that paid back more than was staked. */
    public long wins() {
        return wins;
    }

    /** Everything staked on spins that happened. */
    public long wagered() {
        return wagered;
    }

    /** Everything paid back, stakes of winning bets included. */
    public long paid() {
        return paid;
    }

    /** Paid minus wagered; negative for a player who is behind. */
    public long net() {
        return paid - wagered;
    }

    /** The most one round ever left the player ahead. */
    public long biggestWin() {
        return biggestWin;
    }

    public long winRatePercent() {
        return rounds == 0 ? 0 : Math.round(100.0 * wins / rounds);
    }

    void record(long staked, long payout) {
        rounds++;
        wagered += staked;
        paid += payout;
        if (payout > staked) {
            wins++;
            biggestWin = Math.max(biggestWin, payout - staked);
        }
    }
}
