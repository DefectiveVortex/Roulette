package com.vortex.roulette.model;

import java.util.Set;

/**
 * One pocket of a wheel. {@code id} 0..36 is the number itself, {@link #DOUBLE_ZERO_ID} (37) is the American 00.
 * Instances are shared: get them with {@link #of(int)} and compare with {@code ==} or {@code equals}.
 */
public final class Pocket implements Comparable<Pocket> {
    public static final int DOUBLE_ZERO_ID = 37;

    private static final Set<Integer> RED =
            Set.of(1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36);
    private static final Pocket[] ALL = new Pocket[DOUBLE_ZERO_ID + 1];

    static {
        for (int id = 0; id < ALL.length; id++) {
            ALL[id] = new Pocket(id);
        }
    }

    public static final Pocket ZERO = ALL[0];
    public static final Pocket DOUBLE_ZERO = ALL[DOUBLE_ZERO_ID];

    private final int id;

    private Pocket(int id) {
        this.id = id;
    }

    /** @param id 0..36 for a number, 37 for 00 */
    public static Pocket of(int id) {
        if (id < 0 || id >= ALL.length) {
            throw new IllegalArgumentException("no such pocket: " + id);
        }
        return ALL[id];
    }

    /** Parses {@link #label()}: "0".."36" or "00". */
    public static Pocket parse(String label) {
        return "00".equals(label) ? DOUBLE_ZERO : of(Integer.parseInt(label));
    }

    public int id() {
        return id;
    }

    /** True for 0 and 00. */
    public boolean isZero() {
        return id == 0 || id == DOUBLE_ZERO_ID;
    }

    /** The number 1..36, or 0 for both zero pockets. */
    public int number() {
        return isZero() ? 0 : id;
    }

    /** "0".."36" or "00": what is printed on the felt and the wheel. */
    public String label() {
        return id == DOUBLE_ZERO_ID ? "00" : Integer.toString(id);
    }

    public PocketColor color() {
        if (isZero()) {
            return PocketColor.GREEN;
        }
        return RED.contains(id) ? PocketColor.RED : PocketColor.BLACK;
    }

    /** 1..3 (column 1 holds 1, 4, ... 34; column 3 holds 3, 6, ... 36), or 0 for the zero pockets. */
    public int column() {
        return isZero() ? 0 : (id - 1) % 3 + 1;
    }

    /** 1..3 (1-12, 13-24, 25-36), or 0 for the zero pockets. */
    public int dozen() {
        return isZero() ? 0 : (id - 1) / 12 + 1;
    }

    /** 1..12, the row of three this number sits in (1 = 1-2-3), or 0 for the zero pockets. */
    public int street() {
        return isZero() ? 0 : (id - 1) / 3 + 1;
    }

    @Override
    public int compareTo(Pocket other) {
        return Integer.compare(id, other.id);
    }

    @Override
    public String toString() {
        return label();
    }
}
