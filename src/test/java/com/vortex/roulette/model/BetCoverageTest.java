package com.vortex.roulette.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Which pockets each bet covers, per type, on both wheels. */
class BetCoverageTest {

    private static String pockets(WheelType wheel, String key) {
        return BetSpots.of(wheel).byKey(key).orElseThrow(() -> new AssertionError("no spot " + key + " on " + wheel))
                .pockets().stream().map(Pocket::label).collect(Collectors.joining(" "));
    }

    private static Set<String> keys(WheelType wheel, BetType type) {
        return BetSpots.of(wheel).all().stream().filter(s -> s.type() == type).map(BetSpot::key)
                .collect(Collectors.toSet());
    }

    @ParameterizedTest
    @EnumSource(WheelType.class)
    void straightsCoverTheirOwnPocketOnly(WheelType wheel) {
        for (Pocket pocket : wheel.pockets()) {
            BetSpot spot = BetSpots.of(wheel).straight(pocket);
            assertEquals(BetType.STRAIGHT, spot.type());
            assertEquals(List.of(pocket), spot.pockets());
        }
    }

    @ParameterizedTest
    @EnumSource(WheelType.class)
    void splitsJoinNeighboursOnTheGrid(WheelType wheel) {
        Set<String> splits = keys(wheel, BetType.SPLIT);
        // Across a row of three, and down a column.
        assertTrue(splits.containsAll(Set.of("split:1-2", "split:2-3", "split:35-36", "split:1-4", "split:33-36")));
        // 3 and 4 are on different rows, 34 has nothing below it.
        assertFalse(splits.contains("split:3-4"));
        assertFalse(splits.contains("split:34-37"));
        assertEquals(57, splits.stream().filter(k -> !k.contains(":0-") && !k.endsWith("-00")).count());
    }

    @ParameterizedTest
    @EnumSource(WheelType.class)
    void streetsCornersAndSixLines(WheelType wheel) {
        assertEquals("1 2 3", pockets(wheel, "street:1-2-3"));
        assertEquals("34 35 36", pockets(wheel, "street:34-35-36"));
        assertFalse(keys(wheel, BetType.STREET).contains("street:2-3-4"));

        assertEquals("1 2 4 5", pockets(wheel, "corner:1-2-4-5"));
        assertEquals("32 33 35 36", pockets(wheel, "corner:32-33-35-36"));
        assertFalse(keys(wheel, BetType.CORNER).contains("corner:3-4-6-7"));

        assertEquals("1 2 3 4 5 6", pockets(wheel, "sixline:1-2-3-4-5-6"));
        assertEquals("31 32 33 34 35 36", pockets(wheel, "sixline:31-32-33-34-35-36"));
        assertEquals(11, keys(wheel, BetType.SIX_LINE).size());
    }

    @Test
    void europeanZeroBets() {
        WheelType wheel = WheelType.EUROPEAN;
        assertEquals(Set.of("split:0-1", "split:0-2", "split:0-3"),
                keys(wheel, BetType.SPLIT).stream().filter(k -> k.startsWith("split:0-")).collect(Collectors.toSet()));
        assertEquals(Set.of("trio:0-1-2", "trio:0-2-3"), keys(wheel, BetType.TRIO));
        assertEquals(Set.of("firstfour:0-1-2-3"), keys(wheel, BetType.FIRST_FOUR));
        assertEquals(Set.of(), keys(wheel, BetType.TOP_LINE));
        assertTrue(BetSpots.of(wheel).all().stream().noneMatch(s -> s.covers(Pocket.DOUBLE_ZERO)));
    }

    @Test
    void americanZeroBets() {
        WheelType wheel = WheelType.AMERICAN;
        assertEquals(Set.of("split:0-00", "split:0-1", "split:0-2", "split:2-00", "split:3-00"),
                keys(wheel, BetType.SPLIT).stream().filter(k -> k.startsWith("split:0-") || k.endsWith("-00"))
                        .collect(Collectors.toSet()));
        assertEquals(Set.of("trio:0-1-2", "trio:0-2-00", "trio:2-3-00"), keys(wheel, BetType.TRIO));
        assertEquals(Set.of("topline:0-1-2-3-00"), keys(wheel, BetType.TOP_LINE));
        assertEquals(Set.of(), keys(wheel, BetType.FIRST_FOUR));
    }

    @ParameterizedTest
    @EnumSource(WheelType.class)
    void outsideBets(WheelType wheel) {
        BetSpots spots = BetSpots.of(wheel);
        assertEquals("1 2 3 4 5 6 7 8 9 10 11 12", pockets(wheel, "dozen:1"));
        assertEquals("13 14 15 16 17 18 19 20 21 22 23 24", pockets(wheel, "dozen:2"));
        assertEquals("25 26 27 28 29 30 31 32 33 34 35 36", pockets(wheel, "dozen:3"));
        assertEquals("1 4 7 10 13 16 19 22 25 28 31 34", pockets(wheel, "column:1"));
        assertEquals("2 5 8 11 14 17 20 23 26 29 32 35", pockets(wheel, "column:2"));
        assertEquals("3 6 9 12 15 18 21 24 27 30 33 36", pockets(wheel, "column:3"));
        assertEquals("1 3 5 7 9 12 14 16 18 19 21 23 25 27 30 32 34 36", pockets(wheel, "red"));
        assertEquals("2 4 6 8 10 11 13 15 17 20 22 24 26 28 29 31 33 35", pockets(wheel, "black"));
        assertEquals("1 3 5 7 9 11 13 15 17 19 21 23 25 27 29 31 33 35", pockets(wheel, "odd"));
        assertEquals("2 4 6 8 10 12 14 16 18 20 22 24 26 28 30 32 34 36", pockets(wheel, "even"));
        assertEquals("1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18", pockets(wheel, "low"));
        assertEquals("19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36", pockets(wheel, "high"));
        assertEquals(2, spots.dozen(2).index());
        assertEquals(3, spots.column(3).index());
        assertEquals(0, spots.evenMoney(BetType.RED).index());
        // No outside bet wins on a zero.
        for (BetSpot spot : spots.all()) {
            if (!spot.type().isInside()) {
                assertFalse(spot.covers(Pocket.ZERO) || spot.covers(Pocket.DOUBLE_ZERO), spot.key());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(WheelType.class)
    void everySpotCoversAsManyPocketsAsItsTypeSays(WheelType wheel) {
        for (BetSpot spot : BetSpots.of(wheel).all()) {
            int expected = switch (spot.type()) {
                case STRAIGHT -> 1;
                case SPLIT -> 2;
                case STREET, TRIO -> 3;
                case CORNER, FIRST_FOUR -> 4;
                case TOP_LINE -> 5;
                case SIX_LINE -> 6;
                case DOZEN, COLUMN -> 12;
                case RED, BLACK, ODD, EVEN, LOW, HIGH -> 18;
            };
            assertEquals(expected, spot.pockets().size(), spot.key());
        }
    }

    /**
     * Standard payouts return 36 units per unit staked, summed over all pockets, which is what makes the house edge
     * 1/37 on the European wheel and 2/38 on the American one. The American five-number bet returns only 35.
     */
    @ParameterizedTest
    @EnumSource(WheelType.class)
    void payoutsAreTheStandardOnes(WheelType wheel) {
        assertEquals(List.of(35, 17, 11, 11, 8, 8, 6, 5, 2, 2, 1, 1, 1, 1, 1, 1),
                Arrays.stream(BetType.values()).map(BetType::payout).toList());
        for (BetSpot spot : BetSpots.of(wheel).all()) {
            int returned = spot.pockets().size() * (spot.type().payout() + 1);
            assertEquals(spot.type() == BetType.TOP_LINE ? 35 : 36, returned, spot.key());
        }
    }
}
