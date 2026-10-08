package com.vortex.roulette.model.layout;

import com.vortex.roulette.model.BetSpot;
import com.vortex.roulette.model.BetSpots;
import com.vortex.roulette.model.BetType;
import com.vortex.roulette.model.Pocket;
import com.vortex.roulette.model.WheelType;
import com.vortex.roulette.model.layout.FeltLayout.Placed;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeltLayoutTest {

    private static final FeltLayout EUROPEAN = FeltLayout.of(WheelType.EUROPEAN);
    private static final FeltLayout AMERICAN = FeltLayout.of(WheelType.AMERICAN);
    private static final List<FeltLayout> BOTH = List.of(EUROPEAN, AMERICAN);

    private static Map<BetType, Integer> countByKind(FeltLayout layout) {
        Map<BetType, Integer> counts = new EnumMap<>(BetType.class);
        for (Placed placed : layout.spots()) {
            counts.merge(placed.spot().type(), 1, Integer::sum);
        }
        return counts;
    }

    @Test
    void theFeltHasExactlyTheModelsSpots() {
        for (FeltLayout layout : BOTH) {
            Set<BetSpot> onFelt = new HashSet<>();
            for (Placed placed : layout.spots()) {
                assertTrue(onFelt.add(placed.spot()), placed.spot().key());
            }
            assertEquals(new HashSet<>(BetSpots.of(layout.wheel()).all()), onFelt);
            assertEquals(BetSpots.of(layout.wheel()).all().size(), layout.spots().size());
        }
    }

    private static String idAt(FeltLayout layout, double u, double v) {
        return layout.spotAt(u, v).map(p -> p.spot().key()).orElse("-");
    }

    @Test
    void europeanHasEverySpotOnce() {
        Map<BetType, Integer> counts = countByKind(EUROPEAN);
        assertEquals(37, counts.get(BetType.STRAIGHT));
        assertEquals(57 + 3, counts.get(BetType.SPLIT));
        assertEquals(12, counts.get(BetType.STREET));
        assertEquals(2, counts.get(BetType.TRIO));
        assertEquals(22, counts.get(BetType.CORNER));
        assertEquals(1, counts.get(BetType.FIRST_FOUR));
        assertFalse(counts.containsKey(BetType.TOP_LINE));
        assertEquals(11, counts.get(BetType.SIX_LINE));
        assertEquals(3, counts.get(BetType.COLUMN));
        assertEquals(3, counts.get(BetType.DOZEN));
        assertEquals(157, EUROPEAN.spots().size());
    }

    @Test
    void americanHasEverySpotOnce() {
        Map<BetType, Integer> counts = countByKind(AMERICAN);
        assertEquals(38, counts.get(BetType.STRAIGHT));
        assertEquals(57 + 5, counts.get(BetType.SPLIT));
        assertEquals(12, counts.get(BetType.STREET));
        assertEquals(3, counts.get(BetType.TRIO));
        assertEquals(22, counts.get(BetType.CORNER));
        assertFalse(counts.containsKey(BetType.FIRST_FOUR));
        assertEquals(1, counts.get(BetType.TOP_LINE));
        assertEquals(11, counts.get(BetType.SIX_LINE));
        assertEquals(161, AMERICAN.spots().size());
    }

    @Test
    void everyAnchorIsUniqueOnTheFeltAndPicksItsOwnSpot() {
        for (FeltLayout layout : BOTH) {
            Set<FeltPoint> anchors = new HashSet<>();
            Set<String> ids = new HashSet<>();
            for (Placed placed : layout.spots()) {
                FeltPoint a = placed.anchor();
                assertTrue(a.u() >= 0 && a.u() <= FeltLayout.WIDTH && a.v() >= 0 && a.v() <= FeltLayout.HEIGHT,
                        placed.spot().key());
                assertTrue(anchors.add(a), "two spots share " + a);
                assertTrue(ids.add(placed.spot().key()), "two spots share id " + placed.spot().key());
                assertEquals(Optional.of(placed), layout.spotAt(a), placed.spot().key());
                assertEquals(a, layout.anchor(placed.spot()));
            }
        }
    }

    @Test
    void lineSpotsKeepTheirDistance() {
        // Chips and the aim both need room: no two line spots closer than the tightest pair at the American zero.
        for (FeltLayout layout : BOTH) {
            List<Placed> lines = layout.lineSpots();
            for (int i = 0; i < lines.size(); i++) {
                for (int j = i + 1; j < lines.size(); j++) {
                    FeltPoint a = lines.get(i).anchor();
                    FeltPoint b = lines.get(j).anchor();
                    double min = layout.wheel().hasDoubleZero() ? 0.25 : 0.5;
                    assertTrue(a.distanceSquared(b.u(), b.v()) >= min * min - 1e-9,
                            lines.get(i).spot().key() + " / " + lines.get(j).spot().key());
                }
            }
        }
    }

    @Test
    void numbersSitWhereTheArtDrawsThem() {
        assertEquals(new FeltRect(1, 2, 2, 3), FeltLayout.numberCell(1));
        assertEquals(new FeltRect(1, 0, 2, 1), FeltLayout.numberCell(3));
        assertEquals(new FeltRect(6, 1, 7, 2), FeltLayout.numberCell(17));
        assertEquals(new FeltRect(12, 2, 13, 3), FeltLayout.numberCell(34));
        assertEquals(new FeltRect(12, 0, 13, 1), FeltLayout.numberCell(36));
        assertThrows(IllegalArgumentException.class, () -> FeltLayout.numberCell(0));
        assertThrows(IllegalArgumentException.class, () -> FeltLayout.numberCell(37));
    }

    @Test
    void insideBetsAroundSeventeen() {
        FeltLayout felt = EUROPEAN;
        assertEquals("straight:17", idAt(felt, 6.5, 1.5));
        assertEquals("straight:17", idAt(felt, 6.3, 1.7));
        assertEquals("split:17-20", idAt(felt, 7.0, 1.5));
        assertEquals("split:14-17", idAt(felt, 6.05, 1.4));
        assertEquals("split:17-18", idAt(felt, 6.5, 1.1));
        assertEquals("split:16-17", idAt(felt, 6.6, 1.9));
        assertEquals("corner:17-18-20-21", idAt(felt, 6.9, 1.1));
        assertEquals("corner:13-14-16-17", idAt(felt, 6.1, 2.1));
        assertEquals("street:16-17-18", idAt(felt, 6.5, 3.0));
        assertEquals("street:16-17-18", idAt(felt, 6.4, 2.85));
        assertEquals("sixline:16-17-18-19-20-21", idAt(felt, 7.0, 3.1));
        assertEquals("straight:16", idAt(felt, 6.5, 2.5));
    }

    @Test
    void europeanZeroEnd() {
        FeltLayout felt = EUROPEAN;
        assertEquals("straight:0", idAt(felt, 0.5, 0.2));
        assertEquals("straight:0", idAt(felt, 0.5, 1.5));
        assertEquals("straight:0", idAt(felt, 0.3, 2.9));
        assertEquals("split:0-3", idAt(felt, 1.0, 0.5));
        assertEquals("trio:0-2-3", idAt(felt, 1.0, 1.0));
        assertEquals("split:0-2", idAt(felt, 0.9, 1.5));
        assertEquals("trio:0-1-2", idAt(felt, 1.1, 2.0));
        assertEquals("split:0-1", idAt(felt, 1.0, 2.5));
        assertEquals("firstfour:0-1-2-3", idAt(felt, 1.0, 3.0));
        assertEquals("firstfour:0-1-2-3", idAt(felt, 0.9, 3.1));
    }

    @Test
    void americanZeroEnd() {
        FeltLayout felt = AMERICAN;
        assertEquals("straight:00", idAt(felt, 0.4, 0.7));
        assertEquals("straight:0", idAt(felt, 0.4, 2.3));
        assertEquals("split:0-00", idAt(felt, 0.5, 1.5));
        assertEquals("split:3-00", idAt(felt, 1.0, 0.5));
        assertEquals("trio:2-3-00", idAt(felt, 1.0, 1.0));
        assertEquals("split:2-00", idAt(felt, 1.0, 1.25));
        assertEquals("trio:0-2-00", idAt(felt, 1.0, 1.5));
        assertEquals("split:0-2", idAt(felt, 1.0, 1.75));
        assertEquals("trio:0-1-2", idAt(felt, 1.0, 2.0));
        assertEquals("split:0-1", idAt(felt, 1.0, 2.5));
        assertEquals("topline:0-1-2-3-00", idAt(felt, 1.0, 3.0));
        // between two of the tight spots the nearer one wins
        assertEquals("split:2-00", idAt(felt, 1.0, 1.36));
        assertEquals("trio:0-2-00", idAt(felt, 1.0, 1.39));
        assertEquals(List.of(Pocket.ZERO, Pocket.of(2), Pocket.DOUBLE_ZERO), felt.spotAt(1.0, 1.5).orElseThrow().spot().pockets());
    }

    @Test
    void outsideBets() {
        for (FeltLayout felt : BOTH) {
            assertEquals("column:3", idAt(felt, 13.5, 0.5));
            assertEquals("column:2", idAt(felt, 13.5, 1.5));
            assertEquals("column:1", idAt(felt, 13.5, 2.5));
            assertEquals("dozen:1", idAt(felt, 1.1, 3.6));
            assertEquals("dozen:1", idAt(felt, 4.9, 3.6));
            assertEquals("dozen:2", idAt(felt, 5.1, 3.6));
            assertEquals("dozen:3", idAt(felt, 12.9, 3.9));
            String[] row = {"low", "even", "red", "black", "odd", "high"};
            for (int i = 0; i < row.length; i++) {
                assertEquals(row[i], idAt(felt, 2 + 2 * i, 4.5));
                assertEquals(row[i], idAt(felt, 1.05 + 2 * i, 4.05));
                assertEquals(row[i], idAt(felt, 2.95 + 2 * i, 4.95));
            }
            // a street takes a little of the dozen's top edge, the rest of the dozen is the dozen
            assertEquals("street:1-2-3", idAt(felt, 1.5, 3.2));
            assertEquals("dozen:1", idAt(felt, 1.5, 3.3));
        }
    }

    @Test
    void offTheFeltIsNothing() {
        FeltLayout felt = EUROPEAN;
        assertEquals("-", idAt(felt, 0.5, 4.0));
        assertEquals("-", idAt(felt, 13.5, 4.5));
        assertEquals("-", idAt(felt, -0.3, 1.5));
        assertEquals("-", idAt(felt, 14.3, 1.5));
        assertEquals("-", idAt(felt, 6.5, -0.3));
        assertEquals("-", idAt(felt, 6.5, 5.3));
        assertEquals("-", idAt(felt, Double.NaN, 1));
        // just past an outer edge still counts
        assertEquals("straight:18", idAt(felt, 6.5, -0.1));
        assertEquals("red", idAt(felt, 6.0, 5.1));
        assertEquals("straight:0", idAt(felt, -0.1, 1.5));
        assertEquals("column:2", idAt(felt, 14.1, 1.5));
    }

    @Test
    void anAimInsideACellNeverPicksABetThatMissesThatCell() {
        Random random = new Random(20261008);
        for (FeltLayout felt : BOTH) {
            for (int i = 0; i < 50_000; i++) {
                int number = 1 + random.nextInt(36);
                FeltRect cell = FeltLayout.numberCell(number);
                double u = cell.u0() + random.nextDouble();
                double v = cell.v0() + random.nextDouble();
                Placed picked = felt.spotAt(u, v).orElseThrow();
                assertTrue(picked.spot().covers(Pocket.of(number)),
                        picked.spot().key() + " picked at " + u + "," + v + " inside " + number);
            }
            for (int i = 0; i < 20_000; i++) {
                double u = random.nextDouble();
                double v = random.nextDouble() * 3;
                Placed picked = felt.spotAt(u, v).orElseThrow();
                Pocket zero = felt.wheel().hasDoubleZero() && v < 1.5 ? Pocket.DOUBLE_ZERO : Pocket.ZERO;
                assertTrue(picked.spot().covers(zero), picked.spot().key() + " at " + u + "," + v);
            }
        }
    }

    @Test
    void everyPointOfTheGridBelongsToASpot() {
        for (FeltLayout felt : BOTH) {
            for (double u = 0; u <= 14; u += 0.01) {
                for (double v = 0; v <= 5; v += 0.01) {
                    boolean dead = (u < 1 || u >= 13) && v >= 3;
                    Optional<Placed> picked = felt.spotAt(u, v);
                    if (!dead) {
                        assertTrue(picked.isPresent(), "nothing at " + u + "," + v);
                    } else if (picked.isPresent()) {
                        assertTrue(picked.get().onLine(), picked.get().spot().key() + " at " + u + "," + v);
                    }
                }
            }
        }
    }

    @Test
    void aSpotOfTheOtherWheelIsRejected() {
        BetSpot topLine = BetSpots.of(WheelType.AMERICAN).byKey("topline:0-1-2-3-00").orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> EUROPEAN.anchor(topLine));
        assertTrue(EUROPEAN.find(topLine).isEmpty());
        assertEquals(new FeltPoint(1, 3), AMERICAN.anchor(topLine));
    }
}
