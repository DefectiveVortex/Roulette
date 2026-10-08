package com.vortex.roulette.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vortex.roulette.model.BetSpot;
import com.vortex.roulette.model.BetSpots;
import com.vortex.roulette.model.BetType;
import com.vortex.roulette.model.Pocket;
import com.vortex.roulette.model.WheelType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** What a full round does to a balance, for every bet spot against every pocket of both wheels. */
class PayoutTest {
    private static final long START = 1_000_000;
    private static final long STAKE = 10;

    private final UUID player = UUID.randomUUID();

    private static TableRules rules(WheelType wheel) {
        return new TableRules(wheel, 1, 1000, 0, 20, 20, 20);
    }

    @ParameterizedTest
    @EnumSource(WheelType.class)
    void everySpotAgainstEveryPocket(WheelType wheel) {
        for (BetSpot spot : BetSpots.of(wheel).all()) {
            for (Pocket pocket : wheel.pockets()) {
                ManualClock clock = new ManualClock();
                FakeBank bank = new FakeBank();
                bank.deposit(player, START);
                Round round = new Round("t", rules(wheel), bank, clock, w -> pocket);

                assertEquals(PlaceResult.OK, round.place(player, spot, STAKE));
                clock.advance(60);

                long expected = spot.covers(pocket) ? START + STAKE * spot.type().payout() : START - STAKE;
                assertEquals(expected, bank.balance(player), spot.key() + " on " + pocket);
                assertEquals(0, bank.reservedTotal());
                assertEquals(RoundPhase.IDLE, round.phase());
            }
        }
    }

    @Test
    void severalBetsOfOnePlayerAreSettledTogether() {
        BetSpots spots = BetSpots.of(WheelType.AMERICAN);
        ManualClock clock = new ManualClock();
        FakeBank bank = new FakeBank();
        bank.deposit(player, 1000);
        RoundResult[] seen = new RoundResult[1];
        Round round = new Round("t", rules(WheelType.AMERICAN), bank, clock, w -> Pocket.DOUBLE_ZERO);
        round.addListener(new RoundListener() {
            @Override
            public void resultSettled(Round r, RoundResult result) {
                seen[0] = result;
            }
        });

        round.place(player, spots.straight(Pocket.DOUBLE_ZERO), 5);          // wins 175 + 5
        round.place(player, spots.byKey("split:0-00").orElseThrow(), 10);    // wins 170 + 10
        round.place(player, spots.byKey("topline:0-1-2-3-00").orElseThrow(), 20); // wins 120 + 20
        round.place(player, spots.evenMoney(BetType.EVEN), 100);             // 00 is not even
        round.place(player, spots.column(1), 50);                            // loses
        clock.advance(40);

        assertEquals(1000 - 185 + 180 + 180 + 140, bank.balance(player));
        RoundResult.PlayerResult result = seen[0].players().get(0);
        assertEquals(Pocket.DOUBLE_ZERO, seen[0].pocket());
        assertEquals(185, result.staked());
        assertEquals(500, result.payout());
        assertEquals(315, result.net());
        assertEquals(5, result.bets().size());
        assertTrue(result.bets().get(0).won());
        assertFalse(result.bets().get(3).won());
    }

    @ParameterizedTest
    @EnumSource(WheelType.class)
    void theSecureSourceDrawsEveryPocketOfItsWheelAndNoOther(WheelType wheel) {
        PocketSource source = PocketSource.secure();
        int[] hits = new int[Pocket.DOUBLE_ZERO_ID + 1];
        int draws = wheel.size() * 1000;
        for (int i = 0; i < draws; i++) {
            hits[source.draw(wheel).id()]++;
        }
        for (int id = 0; id < hits.length; id++) {
            if (wheel.has(Pocket.of(id))) {
                // Expected 1000 each; 700..1300 is more than nine standard deviations wide.
                assertTrue(hits[id] > 700 && hits[id] < 1300, "pocket " + id + " drawn " + hits[id] + " times");
            } else {
                assertEquals(0, hits[id]);
            }
        }
    }
}
