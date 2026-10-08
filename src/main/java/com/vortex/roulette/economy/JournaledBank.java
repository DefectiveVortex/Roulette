package com.vortex.roulette.economy;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The real {@link Bank}: every stake is in the {@link StakeJournal} from the moment it leaves the {@link Wallet}
 * until it is refunded or paid, so it comes back exactly once whatever happens to the server. Why each step is in
 * the order it is: {@code docs/MONEY.md}. Server thread only.
 */
public final class JournaledBank implements Bank {

    /** Told when money could not be handed over and when it finally was, so the player can be told too. */
    public interface OwedListener {
        /** A deposit was refused; {@code amount} is kept for the player and retried. */
        default void owed(UUID player, long amount) {}

        /** Money kept for the player (a refused deposit, or stakes of a round the server died in) has arrived. */
        default void delivered(UUID player, long amount) {}
    }

    private final Wallet wallet;
    private final StakeJournal journal;
    private final Logger log;
    private OwedListener listener = new OwedListener() {};

    public JournaledBank(Wallet wallet, StakeJournal journal, Logger log) {
        this.wallet = wallet;
        this.journal = journal;
        this.log = log;
    }

    public void setOwedListener(OwedListener listener) {
        this.listener = listener == null ? new OwedListener() {} : listener;
    }

    public StakeJournal journal() {
        return journal;
    }

    public Wallet wallet() {
        return wallet;
    }

    @Override
    public boolean reserve(UUID roundId, UUID player, long amount) {
        if (amount <= 0 || !wallet.available() || !journal.writable()) {
            return false;
        }
        if (wallet.balance(player) < amount || !wallet.withdraw(player, amount)) {
            return false;
        }
        try {
            journal.reserve(roundId, player, amount);
            return true;
        } catch (IOException e) {
            // A stake that cannot be recorded is not taken.
            if (!wallet.deposit(player, amount)) {
                log.severe("Roulette took " + amount + " from " + player + " but could neither record nor return it."
                        + " Give it back by hand.");
            }
            return false;
        }
    }

    @Override
    public void refund(UUID roundId, UUID player, long amount) {
        long released = journal.refund(roundId, player, amount);
        if (released != amount) {
            log.warning("Roulette refund of " + amount + " to " + player + " in round " + roundId + ": only "
                    + released + " was held, so only that is returned.");
        }
        give(player, released);
    }

    @Override
    public void pay(UUID roundId, UUID player, long staked, long payout) {
        long consumed = journal.pay(roundId, player, payout);
        if (consumed == 0) {
            log.warning("Roulette payout of " + payout + " to " + player + " in round " + roundId
                    + " ignored: no stake is held for it (already refunded or paid).");
            return;
        }
        if (consumed != staked) {
            log.warning("Roulette round " + roundId + ": " + player + " is paid for a stake of " + staked + " but "
                    + consumed + " was held.");
        }
        give(player, payout);
    }

    @Override
    public long balance(UUID player) {
        return wallet.balance(player);
    }

    /**
     * Start-up: stakes of rounds that died with the server become owed to their players. Deliver them with
     * {@link #deliverAll} once the economy is up.
     *
     * @return the total moved
     */
    public long recover() {
        long total = journal.voidHeld();
        if (total > 0) {
            log.warning("Roulette: the server stopped mid-round; " + total + " in stakes is being returned.");
        }
        return total;
    }

    /** Tries to hand over what is owed to this player. Returns what arrived. */
    public long deliver(UUID player) {
        if (journal.owed(player) <= 0 || !wallet.available()) {
            return 0;
        }
        long amount = journal.deliver(player);
        if (!wallet.deposit(player, amount)) {
            journal.owe(player, amount);
            return 0;
        }
        log.info("Roulette returned " + amount + " that was owed to " + player + ".");
        listener.delivered(player, amount);
        return amount;
    }

    /** Tries to hand over everything owed to anyone. Returns what arrived. */
    public long deliverAll() {
        long total = 0;
        for (Map.Entry<UUID, Long> entry : journal.owed().entrySet()) {
            total += deliver(entry.getKey());
        }
        return total;
    }

    private void give(UUID player, long amount) {
        if (amount <= 0) {
            return;
        }
        if (!wallet.available() || !wallet.deposit(player, amount)) {
            journal.owe(player, amount);
            log.warning("Roulette could not give " + amount + " to " + player + " (" + wallet.name()
                    + " refused); it is kept and retried when they join.");
            listener.owed(player, amount);
        }
    }
}
