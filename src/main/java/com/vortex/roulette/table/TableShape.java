package com.vortex.roulette.table;

import com.vortex.roulette.model.layout.FeltFrame;
import com.vortex.roulette.model.layout.FeltFrame.Position;
import com.vortex.roulette.model.layout.FeltLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * The physical table, as offsets from its origin block: {@code a} blocks along the layout (from the wheel end
 * towards the column bets) and {@code b} blocks across it (from the 3-6-9 row towards the even-money bets).
 * Pure numbers, so it is tested without a server.
 *
 * <pre>
 *            a=0        3                    9
 *     b=-1              .  S  .  S  .  S          far stools
 *     b=0    +---------+------------------+
 *            |  wheel  |       felt       | S     end stool
 *     b=3    +---------+------------------+
 *                       S  .  S  .  S  .          near stools (the side the layout faces)
 * </pre>
 *
 * The top is 9x3 full blocks. Stools are a full block high so a seated player's eyes are a block above the felt:
 * from an ordinary half-block chair the far rows are seen too flat to aim at.
 */
public final class TableShape {

    public static final int LENGTH = 9;
    public static final int WIDTH = 3;
    /** The wheel canvas covers a 0..3, the felt canvas a 3..9. */
    public static final int WHEEL_LENGTH = 3;
    public static final double BLOCKS_PER_CELL = (LENGTH - WHEEL_LENGTH) / FeltLayout.CANVAS_WIDTH;
    /** How far a player sits back from the middle of the stool, so their knees stop at the table's edge. */
    public static final double SEAT_SETBACK = 0.2;

    /** A block of the table, relative to its origin block. */
    public record Offset(int a, int b) {
    }

    /**
     * @param block  the stool block
     * @param sitA   where the player sits, in continuous table coordinates
     * @param sitB   where the player sits, in continuous table coordinates
     * @param faceA  the direction the player faces (towards the table), one of -1, 0, 1
     * @param faceB  the direction the player faces (towards the table), one of -1, 0, 1
     */
    public record Seat(int index, Offset block, double sitA, double sitB, int faceA, int faceB) {
    }

    private static final List<Offset> TOP;
    private static final List<Seat> SEATS;

    static {
        List<Offset> top = new ArrayList<>();
        for (int a = 0; a < LENGTH; a++) {
            for (int b = 0; b < WIDTH; b++) {
                top.add(new Offset(a, b));
            }
        }
        TOP = List.copyOf(top);

        List<Seat> seats = new ArrayList<>();
        for (int a = 3; a <= 7; a += 2) {
            seats.add(new Seat(seats.size(), new Offset(a, WIDTH), a + 0.5, WIDTH + 0.5 + SEAT_SETBACK, 0, -1));
        }
        seats.add(new Seat(seats.size(), new Offset(LENGTH, 1), LENGTH + 0.5 + SEAT_SETBACK, 1.5, -1, 0));
        for (int a = 4; a <= 8; a += 2) {
            seats.add(new Seat(seats.size(), new Offset(a, -1), a + 0.5, -0.5 - SEAT_SETBACK, 0, 1));
        }
        SEATS = List.copyOf(seats);
    }

    private TableShape() {
    }

    /** The 27 blocks of the table top. */
    public static List<Offset> top() {
        return TOP;
    }

    /** The seven stools: three on the near side, one at the end, three on the far side. */
    public static List<Seat> seats() {
        return SEATS;
    }

    /** -1 if the block is not a stool. */
    public static int seatAt(int a, int b) {
        for (Seat seat : SEATS) {
            if (seat.block().a() == a && seat.block().b() == b) {
                return seat.index();
            }
        }
        return -1;
    }

    public static boolean isTop(int a, int b) {
        return a >= 0 && a < LENGTH && b >= 0 && b < WIDTH;
    }

    /** Everything a table needs to be clear: its blocks, its stools and the row of floor around them. */
    public static boolean inFootprint(int a, int b) {
        return a >= 0 && a <= LENGTH && b >= -1 && b <= WIDTH;
    }

    /**
     * A table put down in the world: the origin block and the block direction the layout runs in. Across the
     * layout is always that direction turned clockwise seen from above.
     */
    public record Placement(int originX, int originY, int originZ, int uX, int uZ) {

        public Placement {
            if (Math.abs(uX) + Math.abs(uZ) != 1) {
                throw new IllegalArgumentException("not a block direction: " + uX + "," + uZ);
            }
        }

        public int vX() {
            return -uZ;
        }

        public int vZ() {
            return uX;
        }

        public int blockX(int a, int b) {
            return originX + a * uX + b * vX();
        }

        public int blockZ(int a, int b) {
            return originZ + a * uZ + b * vZ();
        }

        /** Offset of a world block from the origin, whatever its height. */
        public Offset offsetOf(int x, int z) {
            int dx = x - originX;
            int dz = z - originZ;
            return new Offset(dx * uX + dz * uZ, dx * vX() + dz * vZ());
        }

        /** Height of the top face of the table blocks. */
        public double topY() {
            return originY + 1;
        }

        /**
         * A point given in continuous table coordinates (the origin block spans 0..1 both ways), {@code lift}
         * blocks above the table top.
         */
        public Position at(double a, double b, double lift) {
            double cornerX = originX + (uX + vX() < 0 ? 1 : 0);
            double cornerZ = originZ + (uZ + vZ() < 0 ? 1 : 0);
            return new Position(cornerX + a * uX + b * vX(), topY() + lift, cornerZ + a * uZ + b * vZ());
        }

        /** The grid of the felt, {@code lift} blocks above the table top. */
        public FeltFrame felt(double lift) {
            Position origin = at(WHEEL_LENGTH + FeltLayout.CANVAS_GRID_U * BLOCKS_PER_CELL,
                    FeltLayout.CANVAS_GRID_V * BLOCKS_PER_CELL, lift);
            return new FeltFrame(origin.x(), origin.y(), origin.z(), uX, uZ, BLOCKS_PER_CELL);
        }

        /** Centre of the felt canvas (6x3 blocks). */
        public Position feltCentre(double lift) {
            return at(WHEEL_LENGTH + (LENGTH - WHEEL_LENGTH) / 2.0, WIDTH / 2.0, lift);
        }

        /** Centre of the wheel canvas (3x3 blocks). */
        public Position wheelCentre(double lift) {
            return at(WHEEL_LENGTH / 2.0, WIDTH / 2.0, lift);
        }

        /** Where the player on this seat sits, at the height of the stool's top face. */
        public Position seat(Seat seat) {
            return at(seat.sitA(), seat.sitB(), 0);
        }

        /**
         * Entity yaw that turns a flat model's texture +x onto the layout direction (east 0, south 90, west 180,
         * north 270). Yaw turns clockwise seen from above.
         */
        public float displayYaw() {
            // an entity at yaw 0 faces south, and the model's +z (texture +y) is the across direction
            return yawOf(vX(), vZ());
        }

        /** Yaw of a player on this seat looking at the table. */
        public float seatYaw(Seat seat) {
            return yawOf(seat.faceA() * uX + seat.faceB() * vX(), seat.faceA() * uZ + seat.faceB() * vZ());
        }

        /** Minecraft yaw of a horizontal direction: south 0, west 90, north 180, east 270. */
        private static float yawOf(int dx, int dz) {
            if (dz > 0) {
                return 0f;
            }
            if (dx < 0) {
                return 90f;
            }
            return dz < 0 ? 180f : 270f;
        }
    }
}
