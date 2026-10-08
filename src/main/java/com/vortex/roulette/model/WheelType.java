package com.vortex.roulette.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The two wheels, each with its pockets in the order they sit on the wheel, clockwise, starting at 0. */
public enum WheelType {
    /** Single zero, 37 pockets. */
    EUROPEAN("0 32 15 19 4 21 2 25 17 34 6 27 13 36 11 30 8 23 10 5 24 16 33 1 20 14 31 9 22 18 29 7 28 12 35 3 26"),
    /** 0 and 00, 38 pockets. */
    AMERICAN("0 28 9 26 30 11 7 20 32 17 5 22 34 15 3 24 36 13 1 00 27 10 25 29 12 8 19 31 18 6 21 33 16 4 23 35 14 2");

    private final List<Pocket> pockets;
    private final int[] indexById = new int[Pocket.DOUBLE_ZERO_ID + 1];

    WheelType(String clockwise) {
        List<Pocket> order = new ArrayList<>();
        java.util.Arrays.fill(indexById, -1);
        for (String label : clockwise.split(" ")) {
            Pocket pocket = Pocket.parse(label);
            indexById[pocket.id()] = order.size();
            order.add(pocket);
        }
        this.pockets = Collections.unmodifiableList(order);
    }

    /** Pockets in wheel order, clockwise; index 0 is the 0 pocket. */
    public List<Pocket> pockets() {
        return pockets;
    }

    /** 37 or 38. */
    public int size() {
        return pockets.size();
    }

    public boolean has(Pocket pocket) {
        return indexById[pocket.id()] >= 0;
    }

    /** Position of the pocket in {@link #pockets()}, or -1 if this wheel does not have it. */
    public int indexOf(Pocket pocket) {
        return indexById[pocket.id()];
    }

    public boolean hasDoubleZero() {
        return this == AMERICAN;
    }
}
