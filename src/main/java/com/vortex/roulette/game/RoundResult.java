package com.vortex.roulette.game;

import com.vortex.roulette.model.BetSpot;
import com.vortex.roulette.model.Pocket;
import java.util.List;
import java.util.UUID;

/** A finished round: the pocket and what it meant for every player who had chips down when betting closed. */
public record RoundResult(UUID roundId, Pocket pocket, List<PlayerResult> players) {

    /**
     * @param staked total of the player's bets
     * @param payout what the bank credited: stake plus winnings of the winning bets, 0 if every bet lost
     */
    public record PlayerResult(UUID player, long staked, long payout, List<BetResult> bets) {
        public long net() {
            return payout - staked;
        }
    }

    /** @param payout stake plus winnings if the spot covered the pocket, otherwise 0 */
    public record BetResult(BetSpot spot, long stake, long payout) {
        public boolean won() {
            return payout > 0;
        }
    }
}
