package com.vortex.roulette.table;

import com.vortex.roulette.model.layout.FeltFrame;
import com.vortex.roulette.model.layout.FeltFrame.Position;
import com.vortex.roulette.model.layout.FeltLayout;
import com.vortex.roulette.table.TableShape.Offset;
import com.vortex.roulette.table.TableShape.Placement;
import com.vortex.roulette.table.TableShape.Seat;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableShapeTest {

    private static final int[][] FACINGS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    @Test
    void sizesFollowTheArtContract() {
        assertEquals(0.375, TableShape.BLOCKS_PER_CELL);
        assertEquals(27, TableShape.top().size());
        assertEquals(7, TableShape.seats().size());
    }

    @Test
    void stoolsStandBesideTheFeltAndNeverInTheTable() {
        Set<Offset> blocks = new HashSet<>(TableShape.top());
        for (Seat seat : TableShape.seats()) {
            Offset block = seat.block();
            assertTrue(blocks.add(block), "stool overlaps something: " + block);
            assertTrue(TableShape.inFootprint(block.a(), block.b()));
            assertFalse(TableShape.isTop(block.a(), block.b()));
            assertEquals(seat.index(), TableShape.seatAt(block.a(), block.b()));
            // one step in the direction faced is a block of the table
            assertTrue(TableShape.isTop(block.a() + seat.faceA(), block.b() + seat.faceB()), "seat " + seat.index());
            // and that block is under the felt, not under the wheel
            assertTrue(block.a() + seat.faceA() >= TableShape.WHEEL_LENGTH);
        }
        assertEquals(-1, TableShape.seatAt(0, 3));
    }

    @Test
    void continuousPointsStayInsideTheirBlockForEveryFacing() {
        for (int[] f : FACINGS) {
            Placement table = new Placement(100, 64, -50, f[0], f[1]);
            for (int a = -1; a <= 9; a++) {
                for (int b = -1; b <= 3; b++) {
                    Position centre = table.at(a + 0.5, b + 0.5, 0);
                    assertEquals(table.blockX(a, b) + 0.5, centre.x(), 1e-9);
                    assertEquals(table.blockZ(a, b) + 0.5, centre.z(), 1e-9);
                    assertEquals(65.0, centre.y());
                    assertEquals(new Offset(a, b), table.offsetOf(table.blockX(a, b), table.blockZ(a, b)));
                }
            }
        }
    }

    @Test
    void theFeltGridLiesCentredOnItsSixByThreeBlocks() {
        for (int[] f : FACINGS) {
            Placement table = new Placement(100, 64, -50, f[0], f[1]);
            FeltFrame felt = table.felt(0);
            Position gridCentre = felt.toWorld(FeltLayout.WIDTH / 2, FeltLayout.HEIGHT / 2);
            Position canvasCentre = table.feltCentre(0);
            assertEquals(canvasCentre.x(), gridCentre.x(), 1e-9);
            assertEquals(canvasCentre.z(), gridCentre.z(), 1e-9);
            // the zero end is the wheel end
            Position zero = felt.toWorld(0, 2.5);
            Position columns = felt.toWorld(14, 2.5);
            Position wheel = table.wheelCentre(0);
            assertTrue(distance(zero, wheel) < distance(columns, wheel));
            // every corner of the grid is on the table top
            for (double[] corner : new double[][] {{0, 0}, {14, 0}, {0, 5}, {14, 5}}) {
                Position p = felt.toWorld(corner[0], corner[1]);
                Offset block = table.offsetOf((int) Math.floor(p.x()), (int) Math.floor(p.z()));
                assertTrue(TableShape.isTop(block.a(), block.b()), block.toString());
                assertTrue(block.a() >= TableShape.WHEEL_LENGTH);
            }
        }
    }

    @Test
    void yawsPointWhereTheyShould() {
        // layout running east: texture +x east, players on the near (south) side look north
        Placement east = new Placement(0, 64, 0, 1, 0);
        assertEquals(0f, east.displayYaw());
        assertEquals(180f, east.seatYaw(TableShape.seats().get(0)));
        assertEquals(90f, east.seatYaw(TableShape.seats().get(3)));
        assertEquals(0f, east.seatYaw(TableShape.seats().get(4)));
        assertEquals(90f, new Placement(0, 64, 0, 0, 1).displayYaw());
        assertEquals(180f, new Placement(0, 64, 0, -1, 0).displayYaw());
        assertEquals(270f, new Placement(0, 64, 0, 0, -1).displayYaw());
    }

    @Test
    void aSeatedPlayerLooksDownOntoTheFarRow() {
        Placement table = new Placement(0, 64, 0, 1, 0);
        FeltFrame felt = table.felt(0);
        Seat seat = TableShape.seats().get(1);
        Position sit = table.seat(seat);
        double eyeY = sit.y() + 1.02;
        Position far = felt.toWorld(6.5, 0.5);
        double flat = Math.hypot(far.x() - sit.x(), far.z() - sit.z());
        double degrees = Math.toDegrees(Math.atan2(eyeY - far.y(), flat));
        assertTrue(degrees > 15, "far row seen at only " + degrees + " degrees");
    }

    private static double distance(Position a, Position b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }
}
