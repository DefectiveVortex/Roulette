package com.vortex.roulette.economy;

import java.util.UUID;

/**
 * The money seam between the round and the economy, in whole currency units. Called on the server thread only.
 *
 * <p>A stake leaves the player's account in {@link #reserve} and from then on belongs to the round {@code roundId}
 * until exactly one of {@link #refund} or {@link #pay} accounts for it. An implementation that journals reservations
 * can therefore return whatever is still reserved after a crash, {@code /stop} or disable, exactly once.
 */
public interface Bank {

    /**
     * Takes {@code amount} from the player as a stake in this round.
     *
     * @return false if it could not be taken (not enough money, economy unavailable); nothing was taken then
     */
    boolean reserve(UUID roundId, UUID player, long amount);

    /** Gives back {@code amount} of what this player still has reserved in this round (chip taken back, player left, round aborted). */
    void refund(UUID roundId, UUID player, long amount);

    /**
     * Settles one player's round after the result. Called exactly once per player who had a stake when betting closed,
     * losers included.
     *
     * @param staked everything the player still had reserved in this round; it is consumed
     * @param payout what the player receives: stake plus winnings of the winning bets, 0 if every bet lost
     */
    void pay(UUID roundId, UUID player, long staked, long payout);

    /** The player's balance, rounded down to whole units. */
    long balance(UUID player);
}
