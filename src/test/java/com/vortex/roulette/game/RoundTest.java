package com.vortex.roulette.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.vortex.roulette.economy.Bank;
import com.vortex.roulette.model.BetSpot;
import com.vortex.roulette.model.BetSpots;
import com.vortex.roulette.model.BetType;
import com.vortex.roulette.model.Pocket;
import com.vortex.roulette.model.WheelType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class RoundTest {
    private static final TableRules RULES = new TableRules(WheelType.EUROPEAN, 5, 500, 10_000, 600, 160, 100);
    private final BetSpots spots = BetSpots.of(WheelType.EUROPEAN);
    private final ManualClock clock = new ManualClock();
    private final FakeBank bank = new FakeBank();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final List<String> events = new ArrayList<>();
    private Pocket next = Pocket.of(17);

    private Round round(TableRules rules) {
        bank.deposit(alice, 1000);
        bank.deposit(bob, 1000);
        Round round = new Round("t1", rules, bank, clock, wheel -> next);
        round.addListener(new RoundListener() {
            @Override public void bettingOpened(Round r, int ticks) { events.add("opened " + ticks); }
            @Override public void betPlaced(Round r, UUID p, BetSpot s, long a, long t) { events.add("placed " + s + " " + a + " " + t); }
            @Override public void betRemoved(Round r, UUID p, BetSpot s, long a, long t) { events.add("removed " + s + " " + a + " " + t); }
            @Override public void betsClosed(Round r) { events.add("closed"); }
            @Override public void spinStarted(Round r, Pocket p, int ticks) { events.add("spin " + p + " " + ticks); }
            @Override public void resultSettled(Round r, RoundResult result) { events.add("result " + result.pocket()); }
            @Override public void cleared(Round r) { events.add("cleared"); }
        });
        return round;
    }

    @Test
    void aFullRoundPaysWinnersAndTakesLosers() {
        Round round = round(RULES);
        UUID first = round.roundId();

        assertEquals(PlaceResult.OK, round.place(alice, spots.straight(Pocket.of(17)), 10));
        assertEquals(PlaceResult.OK, round.place(alice, spots.evenMoney(BetType.RED), 20));
        assertEquals(PlaceResult.OK, round.place(bob, spots.dozen(1), 50));
        assertEquals(RoundPhase.BETTING, round.phase());
        assertEquals(970, bank.balance(alice));
        assertEquals(600, round.ticksLeft());

        clock.advance(600);
        assertEquals(RoundPhase.SPINNING, round.phase());
        assertEquals(PlaceResult.CLOSED, round.place(bob, spots.dozen(1), 50));
        assertEquals(PlaceResult.CLOSED, round.take(bob, spots.dozen(1), 50));

        clock.advance(160);
        assertEquals(RoundPhase.RESULT, round.phase());
        // 17 is black: the straight pays 35:1 plus the stake, red loses, the first dozen (1-12) loses.
        assertEquals(970 + 360, bank.balance(alice));
        assertEquals(950, bank.balance(bob));
        assertEquals(0, bank.reservedTotal());

        clock.advance(100);
        assertEquals(RoundPhase.IDLE, round.phase());
        assertNotEquals(first, round.roundId());
        assertEquals(0, clock.pending());
        assertEquals(List.of("opened 600", "placed straight:17 10 10", "placed red 20 20", "placed dozen:1 50 50",
                "closed", "spin 17 160", "result 17", "cleared"), events);
    }

    @Test
    void limitsAndFundsAreChecked() {
        Round round = round(new TableRules(WheelType.EUROPEAN, 5, 500, 3500, 600, 160, 100));
        BetSpot seven = spots.straight(Pocket.of(7));

        assertEquals(PlaceResult.BELOW_MIN, round.place(alice, seven, 4));
        assertEquals(PlaceResult.INVALID_AMOUNT, round.place(alice, seven, 0));
        assertEquals(PlaceResult.ABOVE_MAX, round.place(alice, spots.evenMoney(BetType.RED), 501));
        assertEquals(PlaceResult.INVALID_SPOT, round.place(alice, BetSpots.of(WheelType.AMERICAN).straight(Pocket.DOUBLE_ZERO), 5));
        assertEquals(RoundPhase.IDLE, round.phase());

        assertEquals(PlaceResult.OK, round.place(alice, seven, 100));
        assertEquals(PlaceResult.ABOVE_MAX_PAYOUT, round.place(alice, seven, 1));
        assertEquals(PlaceResult.OK, round.place(alice, spots.evenMoney(BetType.BLACK), 500));
        assertEquals(PlaceResult.INSUFFICIENT_FUNDS, round.place(alice, spots.evenMoney(BetType.EVEN), 500));
        assertEquals(400, bank.balance(alice));
    }

    @Test
    void aBankThatThrowsDoesNotStopTheRound() {
        UUID carol = UUID.randomUUID();
        Bank broken = new Bank() {
            @Override
            public boolean reserve(UUID roundId, UUID player, long amount) {
                if (player.equals(carol)) {
                    throw new IllegalStateException("economy down");
                }
                return bank.reserve(roundId, player, amount);
            }

            @Override
            public void refund(UUID roundId, UUID player, long amount) {
                bank.refund(roundId, player, amount);
            }

            @Override
            public void pay(UUID roundId, UUID player, long staked, long payout) {
                if (player.equals(alice)) {
                    throw new IllegalStateException("economy down");
                }
                bank.pay(roundId, player, staked, payout);
            }

            @Override
            public long balance(UUID player) {
                return bank.balance(player);
            }
        };
        bank.deposit(alice, 1000);
        bank.deposit(bob, 1000);
        Round round = new Round("t1", RULES, broken, clock, wheel -> next);
        Logger.getLogger("Roulette").setLevel(Level.OFF);
        try {
            assertEquals(PlaceResult.INSUFFICIENT_FUNDS, round.place(carol, spots.straight(Pocket.of(17)), 10));
            assertEquals(RoundPhase.IDLE, round.phase());
            round.place(alice, spots.straight(Pocket.of(17)), 10);
            round.place(bob, spots.straight(Pocket.of(17)), 10);
            clock.advance(600 + 160 + 100);
        } finally {
            Logger.getLogger("Roulette").setLevel(null);
        }

        // Alice's payment failed: bob is still paid, the table still clears, her stake is still held by the bank.
        assertEquals(1350, bank.balance(bob));
        assertEquals(990, bank.balance(alice));
        assertEquals(10, bank.reservedTotal());
        assertEquals(RoundPhase.IDLE, round.phase());
    }

    @Test
    void chipsComeBackWhenTakenLeftOrAborted() {
        Round round = round(RULES);
        BetSpot red = spots.evenMoney(BetType.RED);

        round.place(alice, red, 30);
        assertEquals(PlaceResult.OK, round.take(alice, red, 10));
        assertEquals(20L, round.betsOf(alice).get(red));
        // Taking 16 would leave 4, under the minimum of 5, so all 20 come back.
        assertEquals(PlaceResult.OK, round.take(alice, red, 16));
        assertEquals(PlaceResult.NO_BET, round.take(alice, red, 5));
        assertEquals(1000, bank.balance(alice));

        round.place(bob, red, 40);
        round.leave(bob);
        assertEquals(1000, bank.balance(bob));

        // Nobody has chips down when the countdown ends: no spin, straight back to idle.
        clock.advance(600);
        assertEquals(RoundPhase.IDLE, round.phase());
        assertEquals("cleared", events.get(events.size() - 1));

        round.place(alice, red, 50);
        round.place(bob, spots.column(2), 60);
        clock.advance(600);
        round.abort();
        assertEquals(RoundPhase.IDLE, round.phase());
        assertEquals(1000, bank.balance(alice));
        assertEquals(1000, bank.balance(bob));
        assertEquals(0, bank.reservedTotal());
        assertEquals(0, clock.pending());
    }
}
