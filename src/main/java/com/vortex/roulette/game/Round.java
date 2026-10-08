package com.vortex.roulette.game;

import com.vortex.roulette.economy.Bank;
import com.vortex.roulette.model.BetSpot;
import com.vortex.roulette.model.BetSpots;
import com.vortex.roulette.model.Pocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The round state machine of one table. One instance lives as long as its table and cycles
 * IDLE → BETTING → SPINNING → RESULT → IDLE; each cycle has its own {@link #roundId()}.
 *
 * <p>Pure Java: time comes from a {@link GameClock}, money goes through a {@link Bank}, the result from a
 * {@link PocketSource}. Not thread-safe; call it from the server thread only.
 */
public final class Round {
    private static final Logger LOG = Logger.getLogger("Roulette");

    private final String tableId;
    private final TableRules rules;
    private final BetSpots spots;
    private final Bank bank;
    private final GameClock clock;
    private final PocketSource pockets;
    private final List<RoundListener> listeners = new CopyOnWriteArrayList<>();
    /** Player → spot → stake, both in the order chips first landed. */
    private final Map<UUID, Map<BetSpot, Long>> bets = new LinkedHashMap<>();

    private RoundPhase phase = RoundPhase.IDLE;
    private UUID roundId = UUID.randomUUID();
    private GameClock.Task pending;
    private long phaseEndsAt;
    private Pocket result;

    public Round(String tableId, TableRules rules, Bank bank, GameClock clock, PocketSource pockets) {
        this.tableId = tableId;
        this.rules = rules;
        this.spots = BetSpots.of(rules.wheel());
        this.bank = bank;
        this.clock = clock;
        this.pockets = pockets;
    }

    public String tableId() {
        return tableId;
    }

    public TableRules rules() {
        return rules;
    }

    /** The bet spots of this table's wheel. */
    public BetSpots spots() {
        return spots;
    }

    public RoundPhase phase() {
        return phase;
    }

    /** Identifies the current cycle; a new one is assigned every time the table clears. */
    public UUID roundId() {
        return roundId;
    }

    /** Ticks until the current phase ends on its own; 0 while IDLE. */
    public long ticksLeft() {
        return phase == RoundPhase.IDLE ? 0 : Math.max(0, phaseEndsAt - clock.now());
    }

    /** The drawn pocket while SPINNING or RESULT, otherwise null. */
    public Pocket result() {
        return result;
    }

    public void addListener(RoundListener listener) {
        listeners.add(listener);
    }

    public void removeListener(RoundListener listener) {
        listeners.remove(listener);
    }

    /** Players with chips on the table, in the order they first bet. */
    public Set<UUID> bettors() {
        return Collections.unmodifiableSet(bets.keySet());
    }

    /** The player's stakes by spot; empty if none. A read-only view. */
    public Map<BetSpot, Long> betsOf(UUID player) {
        Map<BetSpot, Long> own = bets.get(player);
        return own == null ? Map.of() : Collections.unmodifiableMap(own);
    }

    public long stakeOf(UUID player) {
        long total = 0;
        for (long stake : betsOf(player).values()) {
            total += stake;
        }
        return total;
    }

    /**
     * Puts {@code amount} more on a spot. The stake is taken from the player here, through the bank. The first chip
     * on an idle table opens betting.
     */
    public PlaceResult place(UUID player, BetSpot spot, long amount) {
        if (phase == RoundPhase.SPINNING || phase == RoundPhase.RESULT) {
            return PlaceResult.CLOSED;
        }
        if (!spots.contains(spot)) {
            return PlaceResult.INVALID_SPOT;
        }
        if (amount <= 0) {
            return PlaceResult.INVALID_AMOUNT;
        }
        long onSpot = betsOf(player).getOrDefault(spot, 0L) + amount;
        if (onSpot < rules.minBet()) {
            return PlaceResult.BELOW_MIN;
        }
        if (onSpot > rules.maxBet()) {
            return PlaceResult.ABOVE_MAX;
        }
        if (rules.maxPayout() > 0 && bestWin(player, spot, amount) > rules.maxPayout()) {
            return PlaceResult.ABOVE_MAX_PAYOUT;
        }
        if (!reserve(player, amount)) {
            return PlaceResult.INSUFFICIENT_FUNDS;
        }
        if (phase == RoundPhase.IDLE) {
            phase = RoundPhase.BETTING;
            schedule(rules.bettingTicks(), this::closeBets);
            fire(l -> l.bettingOpened(this, rules.bettingTicks()));
        }
        bets.computeIfAbsent(player, p -> new LinkedHashMap<>()).put(spot, onSpot);
        fire(l -> l.betPlaced(this, player, spot, amount, onSpot));
        return PlaceResult.OK;
    }

    /**
     * Takes up to {@code amount} back off a spot and refunds it. If what would remain is under the table minimum, the
     * whole stake on the spot comes back.
     */
    public PlaceResult take(UUID player, BetSpot spot, long amount) {
        if (phase != RoundPhase.BETTING) {
            return phase == RoundPhase.IDLE ? PlaceResult.NO_BET : PlaceResult.CLOSED;
        }
        if (amount <= 0) {
            return PlaceResult.INVALID_AMOUNT;
        }
        long onSpot = betsOf(player).getOrDefault(spot, 0L);
        if (onSpot == 0) {
            return PlaceResult.NO_BET;
        }
        long left = onSpot - Math.min(amount, onSpot);
        if (left < rules.minBet()) {
            left = 0;
        }
        removeStake(player, spot, onSpot, left);
        return PlaceResult.OK;
    }

    /**
     * The player left the table. While betting is open all their chips are refunded; once it has closed their bets
     * stand and are paid like anyone else's.
     */
    public void leave(UUID player) {
        if (phase != RoundPhase.BETTING) {
            return;
        }
        for (Map.Entry<BetSpot, Long> bet : new ArrayList<>(betsOf(player).entrySet())) {
            removeStake(player, bet.getKey(), bet.getValue(), 0);
        }
    }

    /**
     * Stops the round wherever it is (plugin disable, table removed). Every stake that has not been paid out yet is
     * refunded, also when the result was already drawn, and the table clears.
     */
    public void abort() {
        if (phase == RoundPhase.IDLE) {
            return;
        }
        cancelPending();
        if (phase == RoundPhase.BETTING || phase == RoundPhase.SPINNING) {
            for (UUID player : new ArrayList<>(bets.keySet())) {
                long staked = stakeOf(player);
                if (staked > 0) {
                    try {
                        bank.refund(roundId, player, staked);
                    } catch (RuntimeException e) {
                        LOG.log(Level.SEVERE, "Roulette: refunding " + staked + " to " + player + " failed", e);
                    }
                }
            }
        }
        clear();
    }

    /** A bank that throws took nothing as far as the round is concerned. */
    private boolean reserve(UUID player, long amount) {
        try {
            return bank.reserve(roundId, player, amount);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Roulette: taking a stake of " + amount + " from " + player + " failed", e);
            return false;
        }
    }

    private void removeStake(UUID player, BetSpot spot, long onSpot, long left) {
        long removed = onSpot - left;
        // Refund first: if the bank throws, the chips are still on the table and nothing is lost.
        bank.refund(roundId, player, removed);
        Map<BetSpot, Long> own = bets.get(player);
        if (left == 0) {
            own.remove(spot);
            if (own.isEmpty()) {
                bets.remove(player);
            }
        } else {
            own.put(spot, left);
        }
        fire(l -> l.betRemoved(this, player, spot, removed, left));
    }

    /** The most the player could win on one pocket if {@code amount} more went on {@code spot}. */
    private long bestWin(UUID player, BetSpot spot, long amount) {
        Map<BetSpot, Long> own = new LinkedHashMap<>(betsOf(player));
        own.merge(spot, amount, Long::sum);
        long best = 0;
        for (Pocket pocket : rules.wheel().pockets()) {
            long win = 0;
            for (Map.Entry<BetSpot, Long> bet : own.entrySet()) {
                if (bet.getKey().covers(pocket)) {
                    win += bet.getValue() * bet.getKey().type().payout();
                }
            }
            best = Math.max(best, win);
        }
        return best;
    }

    private void closeBets() {
        pending = null;
        if (bets.isEmpty()) {
            clear();
            return;
        }
        phase = RoundPhase.SPINNING;
        result = pockets.draw(rules.wheel());
        Pocket drawn = result;
        schedule(rules.spinTicks(), this::settle);
        fire(l -> l.betsClosed(this));
        fire(l -> l.spinStarted(this, drawn, rules.spinTicks()));
    }

    private void settle() {
        pending = null;
        List<RoundResult.PlayerResult> players = new ArrayList<>();
        for (Map.Entry<UUID, Map<BetSpot, Long>> entry : bets.entrySet()) {
            List<RoundResult.BetResult> outcomes = new ArrayList<>();
            long staked = 0;
            long payout = 0;
            for (Map.Entry<BetSpot, Long> bet : entry.getValue().entrySet()) {
                BetSpot spot = bet.getKey();
                long stake = bet.getValue();
                long back = spot.covers(result) ? stake * (spot.type().payout() + 1L) : 0;
                outcomes.add(new RoundResult.BetResult(spot, stake, back));
                staked += stake;
                payout += back;
            }
            try {
                bank.pay(roundId, entry.getKey(), staked, payout);
            } catch (RuntimeException e) {
                // The others must still be paid and the table must clear. The stake stays reserved in the bank.
                LOG.log(Level.SEVERE, "Roulette: paying " + payout + " to " + entry.getKey() + " failed", e);
            }
            players.add(new RoundResult.PlayerResult(entry.getKey(), staked, payout, List.copyOf(outcomes)));
        }
        RoundResult summary = new RoundResult(roundId, result, List.copyOf(players));
        phase = RoundPhase.RESULT;
        schedule(rules.resultTicks(), this::clear);
        fire(l -> l.resultSettled(this, summary));
    }

    private void clear() {
        pending = null;
        bets.clear();
        result = null;
        phase = RoundPhase.IDLE;
        fire(l -> l.cleared(this));
        roundId = UUID.randomUUID();
    }

    private void schedule(long ticks, Runnable next) {
        phaseEndsAt = clock.now() + ticks;
        pending = clock.runLater(ticks, next);
    }

    private void cancelPending() {
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
    }

    private void fire(Consumer<RoundListener> event) {
        for (RoundListener listener : listeners) {
            try {
                event.accept(listener);
            } catch (RuntimeException | LinkageError e) {
                LOG.log(Level.SEVERE, "Roulette listener " + listener.getClass().getName() + " failed", e);
            }
        }
    }
}
