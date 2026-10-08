package com.vortex.roulette.model.layout;

import com.vortex.roulette.model.BetSpots;
import com.vortex.roulette.model.Pocket;
import com.vortex.roulette.model.WheelType;
import com.vortex.roulette.model.layout.FeltFrame.Position;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeltFrameTest {

    private static final double S = 0.375;
    private static final int[][] FACINGS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    @Test
    void vIsUTurnedClockwiseSeenFromAbove() {
        // east -> south -> west -> north, with +z being south
        assertV(1, 0, 0, 1);
        assertV(0, 1, -1, 0);
        assertV(-1, 0, 0, -1);
        assertV(0, -1, 1, 0);
    }

    private static void assertV(int uX, int uZ, int vX, int vZ) {
        FeltFrame frame = new FeltFrame(0, 0, 0, uX, uZ, S);
        assertEquals(vX, frame.vX());
        assertEquals(vZ, frame.vZ());
    }

    @Test
    void feltToWorldAndBack() {
        for (int[] f : FACINGS) {
            FeltFrame frame = new FeltFrame(100.5, 65.0, -20.25, f[0], f[1], S);
            for (double u = 0; u <= 14; u += 0.7) {
                for (double v = 0; v <= 5; v += 0.7) {
                    Position p = frame.toWorld(u, v);
                    assertEquals(65.0, p.y());
                    FeltPoint back = frame.toFelt(p.x(), p.z());
                    assertEquals(u, back.u(), 1e-9);
                    assertEquals(v, back.v(), 1e-9);
                }
            }
        }
    }

    @Test
    void eastFacingFeltLiesWhereExpected() {
        FeltFrame frame = new FeltFrame(10, 64, 20, 1, 0, S);
        assertEquals(new Position(10 + 14 * S, 64, 20), frame.toWorld(14, 0));
        assertEquals(new Position(10, 64, 20 + 5 * S), frame.toWorld(0, 5));
        assertEquals(new Position(10 + S, 64.1, 20 + S), frame.toWorld(1, 1, 0.1));
    }

    @Test
    void lookingStraightDownHitsThePointBelowTheEye() {
        for (int[] f : FACINGS) {
            FeltFrame frame = new FeltFrame(10, 64, 20, f[0], f[1], S);
            Position target = frame.toWorld(6.5, 1.5);
            FeltPoint hit = frame.hit(target.x(), 66, target.z(), 0, -1, 0, 8).orElseThrow();
            assertEquals(6.5, hit.u(), 1e-9);
            assertEquals(1.5, hit.v(), 1e-9);
        }
    }

    @Test
    void aSlantedLookHitsWhereItIsAimed() {
        for (int[] f : FACINGS) {
            FeltFrame frame = new FeltFrame(10, 64, 20, f[0], f[1], S);
            Position target = frame.toWorld(12.25, 4.5);
            double eyeX = 7.3, eyeY = 65.62, eyeZ = 23.9;
            // deliberately not normalised: the ray's length must not matter
            double dx = (target.x() - eyeX) * 3, dy = (target.y() - eyeY) * 3, dz = (target.z() - eyeZ) * 3;
            FeltPoint hit = frame.hit(eyeX, eyeY, eyeZ, dx, dy, dz, 12).orElseThrow();
            assertEquals(12.25, hit.u(), 1e-9);
            assertEquals(4.5, hit.v(), 1e-9);
        }
    }

    @Test
    void noHitWhenLookingAwayOrFromBelowOrTooFar() {
        FeltFrame frame = new FeltFrame(10, 64, 20, 1, 0, S);
        assertEquals(Optional.empty(), frame.hit(11, 66, 21, 0, 1, 0, 8));
        assertEquals(Optional.empty(), frame.hit(11, 66, 21, 1, 0, 0, 8));
        assertEquals(Optional.empty(), frame.hit(11, 63, 21, 0, -1, 0, 8));
        assertEquals(Optional.empty(), frame.hit(11, 64, 21, 0, -1, 0, 8));
        assertEquals(Optional.empty(), frame.hit(11, 66, 21, 1, -0.01, 0, 8));
        assertEquals(Optional.empty(), frame.hit(11, 66, 21, Double.NaN, -1, 0, 8));
        assertTrue(frame.hit(11, 66, 21, 0, -1, 0, 2).isPresent());
        assertEquals(Optional.empty(), frame.hit(11, 66, 21, 0, -1, 0, 1.99));
    }

    @Test
    void theHitFeedsTheLayout() {
        FeltFrame frame = new FeltFrame(10, 64, 20, 0, 1, S);
        FeltLayout felt = FeltLayout.of(WheelType.EUROPEAN);
        Position seventeen = frame.toWorld(felt.anchor(BetSpots.of(WheelType.EUROPEAN).straight(Pocket.of(17))));
        FeltPoint hit = frame.hit(seventeen.x() + 1, 65.5, seventeen.z() - 1,
                -1, -1.5, 1, 8).orElseThrow();
        assertEquals("straight:17", felt.spotAt(hit).orElseThrow().spot().key());
    }

    @Test
    void onlyBlockDirectionsAndPositiveScale() {
        assertThrows(IllegalArgumentException.class, () -> new FeltFrame(0, 0, 0, 1, 1, S));
        assertThrows(IllegalArgumentException.class, () -> new FeltFrame(0, 0, 0, 0, 0, S));
        assertThrows(IllegalArgumentException.class, () -> new FeltFrame(0, 0, 0, 2, -1, S));
        assertThrows(IllegalArgumentException.class, () -> new FeltFrame(0, 0, 0, 1, 0, 0));
    }
}
