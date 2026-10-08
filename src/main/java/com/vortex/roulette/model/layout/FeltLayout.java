package com.vortex.roulette.model.layout;

import com.vortex.roulette.model.BetSpot;
import com.vortex.roulette.model.BetSpots;
import com.vortex.roulette.model.BetType;
import com.vortex.roulette.model.Pocket;
import com.vortex.roulette.model.WheelType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where every bet lies on the felt, for one wheel type. Pure geometry in cell units (see {@link FeltPoint}):
 *
 * <pre>
 *        u=0   1                                   13   14
 *   v=0   +----+--+--+--+--+--+--+--+--+--+--+--+--+----+
 *         |    | 3| 6| 9|12|15|18|21|24|27|30|33|36|2to1|
 *     1   | 0  +--+--+--+--+--+--+--+--+--+--+--+--+----+
 *         |(00 | 2| 5| 8|11|14|17|20|23|26|29|32|35|2to1|
 *     2   | /0)+--+--+--+--+--+--+--+--+--+--+--+--+----+
 *         |    | 1| 4| 7|10|13|16|19|22|25|28|31|34|2to1|
 *     3   +----+--+--+--+--+--+--+--+--+--+--+--+--+----+
 *              |  1st 12   |  2nd 12   |  3rd 12   |
 *     4        +-----+-----+-----+-----+-----+-----+
 *              |1-18 |EVEN | RED |BLACK| ODD |19-36|
 *     5        +-----+-----+-----+-----+-----+-----+
 * </pre>
 *
 * The same grid is drawn on the felt texture (docs/ART-CONTRACT.md): a canvas of 16x8 cells with this grid's
 * origin at cell (1, 1.5), which is pixel (64,96) of the 1024x512 texture at 64 px per cell.
 */
public final class FeltLayout {

    private static final int OO = Pocket.DOUBLE_ZERO_ID;

    public static final double WIDTH = 14;
    public static final double HEIGHT = 5;

    /** The felt texture is larger than the grid: 16x8 units, with the grid starting here. */
    public static final double CANVAS_WIDTH = 16;
    public static final double CANVAS_HEIGHT = 8;
    public static final double CANVAS_GRID_U = 1;
    public static final double CANVAS_GRID_V = 1.5;

    /** A bet on a line or a corner is taken when the aim is this close to it; otherwise the cell under the aim. */
    public static final double SNAP_RADIUS = 0.25;
    /** Aiming this far past the outer edge still counts as the edge. */
    public static final double EDGE_TOLERANCE = 0.2;

    private static final Map<WheelType, FeltLayout> BY_WHEEL = new EnumMap<>(WheelType.class);

    static {
        for (WheelType wheel : WheelType.values()) {
            BY_WHEEL.put(wheel, new FeltLayout(wheel));
        }
    }

    /** A spot with its place: where a chip rests, and for a cell bet the cell that selects it. */
    public record Placed(BetSpot spot, FeltPoint anchor, FeltRect cell) {
        public boolean onLine() {
            return cell == null;
        }
    }

    private final WheelType wheel;
    private final BetSpots betSpots;
    private final Map<BetSpot, Placed> bySpot = new LinkedHashMap<>();
    private final List<Placed> cells = new ArrayList<>();
    private final List<Placed> lines = new ArrayList<>();

    public static FeltLayout of(WheelType wheel) {
        return BY_WHEEL.get(wheel);
    }

    private FeltLayout(WheelType wheel) {
        this.wheel = wheel;
        this.betSpots = BetSpots.of(wheel);
        zeroEnd();
        numbers();
        outside();
    }

    public WheelType wheel() {
        return wheel;
    }

    /** Every spot of this wheel, cells and lines. */
    public List<Placed> spots() {
        return List.copyOf(bySpot.values());
    }

    public Optional<Placed> find(BetSpot spot) {
        return Optional.ofNullable(bySpot.get(spot));
    }

    /** Where a chip on this spot rests. */
    public FeltPoint anchor(BetSpot spot) {
        Placed placed = bySpot.get(spot);
        if (placed == null) {
            throw new IllegalArgumentException("no such spot on the " + wheel + " felt: " + spot.key());
        }
        return placed.anchor();
    }

    /** The spot a chip dropped at this point belongs to, if the point is on the felt. */
    public Optional<Placed> spotAt(double u, double v) {
        if (Double.isNaN(u) || Double.isNaN(v)
                || u < -EDGE_TOLERANCE || u > WIDTH + EDGE_TOLERANCE
                || v < -EDGE_TOLERANCE || v > HEIGHT + EDGE_TOLERANCE) {
            return Optional.empty();
        }
        Placed nearest = null;
        double best = SNAP_RADIUS * SNAP_RADIUS;
        for (Placed line : lines) {
            double d = line.anchor().distanceSquared(u, v);
            if (d < best) {
                best = d;
                nearest = line;
            }
        }
        if (nearest != null) {
            return Optional.of(nearest);
        }
        double cu = Math.min(Math.max(u, 0), Math.nextDown(WIDTH));
        double cv = Math.min(Math.max(v, 0), Math.nextDown(HEIGHT));
        for (Placed cell : cells) {
            if (cell.cell().contains(cu, cv)) {
                return Optional.of(cell);
            }
        }
        return Optional.empty();
    }

    public Optional<Placed> spotAt(FeltPoint point) {
        return spotAt(point.u(), point.v());
    }

    /** The cell of a number 1-36. */
    public static FeltRect numberCell(int number) {
        if (number < 1 || number > 36) {
            throw new IllegalArgumentException("not a number on the grid: " + number);
        }
        int street = street(number);
        int row = row(number);
        return new FeltRect(1 + street, row, 2 + street, row + 1);
    }

    /** 0 for 1-2-3 up to 11 for 34-35-36. */
    private static int street(int number) {
        return (number - 1) / 3;
    }

    /** 0 for the 3-6-9 row at the top, 2 for the 1-4-7 row. */
    private static int row(int number) {
        return 2 - (number - 1) % 3;
    }

    private void zeroEnd() {
        if (wheel.hasDoubleZero()) {
            cell(inside(OO), new FeltRect(0, 0, 1, 1.5));
            cell(inside(0), new FeltRect(0, 1.5, 1, 3));
            line(inside(0, OO), 0.5, 1.5);
            line(inside(OO, 3), 1, 0.5);
            line(inside(OO, 2, 3), 1, 1);
            line(inside(OO, 2), 1, 1.25);
            line(inside(0, OO, 2), 1, 1.5);
            line(inside(0, 2), 1, 1.75);
            line(inside(0, 1, 2), 1, 2);
            line(inside(0, 1), 1, 2.5);
            line(inside(0, OO, 1, 2, 3), 1, 3);
        } else {
            cell(inside(0), new FeltRect(0, 0, 1, 3));
            line(inside(0, 3), 1, 0.5);
            line(inside(0, 2, 3), 1, 1);
            line(inside(0, 2), 1, 1.5);
            line(inside(0, 1, 2), 1, 2);
            line(inside(0, 1), 1, 2.5);
            line(inside(0, 1, 2, 3), 1, 3);
        }
    }

    private void numbers() {
        for (int n = 1; n <= 36; n++) {
            cell(inside(n), numberCell(n));
        }
        for (int n = 1; n <= 36; n++) {
            int street = street(n);
            int row = row(n);
            boolean hasAbove = n % 3 != 0;
            boolean hasRight = n + 3 <= 36;
            if (hasRight) {
                line(inside(n, n + 3), 2 + street, row + 0.5);
            }
            if (hasAbove) {
                line(inside(n, n + 1), 1.5 + street, row);
            }
            if (hasAbove && hasRight) {
                line(inside(n, n + 1, n + 3, n + 4), 2 + street, row);
            }
        }
        for (int street = 0; street < 12; street++) {
            int first = 3 * street + 1;
            line(inside(first, first + 1, first + 2), 1.5 + street, 3);
            if (street < 11) {
                line(inside(first, first + 1, first + 2, first + 3, first + 4, first + 5),
                        2 + street, 3);
            }
        }
    }

    private void outside() {
        for (int column = 1; column <= 3; column++) {
            int row = 3 - column;
            cell(betSpots.column(column), new FeltRect(13, row, 14, row + 1));
        }
        for (int dozen = 1; dozen <= 3; dozen++) {
            double left = 1 + 4 * (dozen - 1);
            cell(betSpots.dozen(dozen), new FeltRect(left, 3, left + 4, 4));
        }
        BetType[] evenMoney = {BetType.LOW, BetType.EVEN, BetType.RED, BetType.BLACK, BetType.ODD, BetType.HIGH};
        for (int i = 0; i < evenMoney.length; i++) {
            cell(betSpots.evenMoney(evenMoney[i]), new FeltRect(1 + 2 * i, 4, 3 + 2 * i, 5));
        }
    }

    /** The model's spot covering exactly these pockets; the model, not this class, says what a bet is. */
    private BetSpot inside(int... ids) {
        List<Pocket> pockets = new ArrayList<>();
        for (int id : ids) {
            pockets.add(Pocket.of(id));
        }
        return betSpots.covering(pockets).orElseThrow(
                () -> new IllegalStateException("the " + wheel + " wheel has no bet on " + pockets));
    }

    private void cell(BetSpot spot, FeltRect rect) {
        add(new Placed(spot, rect.centre(), rect), cells);
    }

    private void line(BetSpot spot, double u, double v) {
        add(new Placed(spot, new FeltPoint(u, v), null), lines);
    }

    private void add(Placed placed, List<Placed> into) {
        if (bySpot.putIfAbsent(placed.spot(), placed) != null) {
            throw new IllegalStateException("spot laid out twice: " + placed.spot().key());
        }
        into.add(placed);
    }

    List<Placed> lineSpots() {
        return Collections.unmodifiableList(lines);
    }
}
