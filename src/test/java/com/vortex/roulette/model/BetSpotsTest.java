package com.vortex.roulette.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BetSpotsTest {

    private static Map<BetType, Integer> count(WheelType wheel) {
        Map<BetType, Integer> counts = new EnumMap<>(BetType.class);
        for (BetSpot spot : BetSpots.of(wheel).all()) {
            counts.merge(spot.type(), 1, Integer::sum);
        }
        return counts;
    }

    @Test
    void europeanFeltHasEverySpot() {
        Map<BetType, Integer> counts = count(WheelType.EUROPEAN);
        assertEquals(37, counts.get(BetType.STRAIGHT));
        assertEquals(60, counts.get(BetType.SPLIT));
        assertEquals(12, counts.get(BetType.STREET));
        assertEquals(2, counts.get(BetType.TRIO));
        assertEquals(22, counts.get(BetType.CORNER));
        assertEquals(1, counts.get(BetType.FIRST_FOUR));
        assertEquals(null, counts.get(BetType.TOP_LINE));
        assertEquals(11, counts.get(BetType.SIX_LINE));
        assertEquals(3, counts.get(BetType.DOZEN));
        assertEquals(3, counts.get(BetType.COLUMN));
        assertEquals(157, BetSpots.of(WheelType.EUROPEAN).all().size());
    }

    @Test
    void americanFeltHasEverySpot() {
        Map<BetType, Integer> counts = count(WheelType.AMERICAN);
        assertEquals(38, counts.get(BetType.STRAIGHT));
        assertEquals(62, counts.get(BetType.SPLIT));
        assertEquals(3, counts.get(BetType.TRIO));
        assertEquals(null, counts.get(BetType.FIRST_FOUR));
        assertEquals(1, counts.get(BetType.TOP_LINE));
        assertEquals(161, BetSpots.of(WheelType.AMERICAN).all().size());
    }

    @Test
    void spotsResolveByKeyAndByPockets() {
        for (WheelType wheel : WheelType.values()) {
            BetSpots spots = BetSpots.of(wheel);
            for (BetSpot spot : spots.all()) {
                assertSame(spot, spots.byKey(spot.key()).orElseThrow());
                assertSame(spot, spots.covering(spot.pockets()).orElseThrow());
                assertTrue(spot.pockets().stream().allMatch(wheel::has), spot.key());
            }
        }
        assertEquals("split:0-00", BetSpots.of(WheelType.AMERICAN)
                .covering(java.util.List.of(Pocket.DOUBLE_ZERO, Pocket.ZERO)).orElseThrow().key());
        assertEquals("topline:0-1-2-3-00", BetSpots.of(WheelType.AMERICAN).all().stream()
                .filter(s -> s.type() == BetType.TOP_LINE).findFirst().orElseThrow().key());
    }

    @Test
    void wheelsHaveEveryPocketOnce() {
        assertEquals(37, WheelType.EUROPEAN.pockets().stream().distinct().count());
        assertEquals(38, WheelType.AMERICAN.pockets().stream().distinct().count());
        assertEquals(18, WheelType.AMERICAN.pockets().stream().filter(p -> p.color() == PocketColor.RED).count());
        assertEquals(Pocket.ZERO, WheelType.EUROPEAN.pockets().get(0));
        assertEquals(-1, WheelType.EUROPEAN.indexOf(Pocket.DOUBLE_ZERO));
    }
}
