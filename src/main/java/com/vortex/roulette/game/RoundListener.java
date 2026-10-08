package com.vortex.roulette.game;

import com.vortex.roulette.model.BetSpot;
import com.vortex.roulette.model.Pocket;
import java.util.UUID;

/**
 * What a round tells the outside world, in the order things happen. The felt view, the wheel view, messages and
 * stats all implement this. Calls arrive on the server thread. A listener must not throw; if it does, the round
 * logs it and carries on, so a broken view can never touch money.
 *
 * <p>One round: {@code bettingOpened}, any number of {@code betPlaced} / {@code betRemoved}, then
 * {@code betsClosed}, {@code spinStarted}, {@code resultSettled}, {@code cleared}. A round whose chips were all taken
 * back, or that was aborted, goes straight to {@code cleared}.
 */
public interface RoundListener {

    /** The first chip is about to land; betting closes in {@code countdownTicks}. */
    default void bettingOpened(Round round, int countdownTicks) {}

    /**
     * @param amount         what was just added
     * @param playerOnSpot   this player's total on the spot afterwards
     */
    default void betPlaced(Round round, UUID player, BetSpot spot, long amount, long playerOnSpot) {}

    /**
     * Chips were taken back or refunded (the player left during betting).
     *
     * @param amount         what was just removed
     * @param playerOnSpot   this player's total on the spot afterwards, 0 if nothing is left
     */
    default void betRemoved(Round round, UUID player, BetSpot spot, long amount, long playerOnSpot) {}

    /** No more bets. */
    default void betsClosed(Round round) {}

    /**
     * The result is already decided: animate the wheel and ball so they come to rest on {@code result} after
     * {@code durationTicks}. The payout happens then whether or not the animation kept up.
     */
    default void spinStarted(Round round, Pocket result, int durationTicks) {}

    /** The bank has paid everyone; show the winning number and who won what. */
    default void resultSettled(Round round, RoundResult result) {}

    /** The table is empty again and the next chip opens a new round. Remove chips, markers and countdowns. */
    default void cleared(Round round) {}
}
