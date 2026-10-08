package com.vortex.roulette.model;

import java.util.List;
import java.util.Set;

/**
 * One place on the felt a chip can sit: a bet type and the pockets it covers. Spots come from {@link BetSpots} only,
 * so two spots are equal exactly when their keys are equal.
 */
public final class BetSpot {
    private final BetType type;
    private final String key;
    private final List<Pocket> pockets;
    private final Set<Pocket> pocketSet;

    private final int index;

    BetSpot(BetType type, String key, int index, List<Pocket> sortedPockets) {
        this.type = type;
        this.key = key;
        this.index = index;
        this.pockets = List.copyOf(sortedPockets);
        this.pocketSet = Set.copyOf(sortedPockets);
    }

    public BetType type() {
        return type;
    }

    /**
     * Stable identifier, safe to store: {@code straight:17}, {@code split:0-00}, {@code corner:1-2-4-5},
     * {@code dozen:2}, {@code column:3}, {@code red}. Resolve with {@link BetSpots#byKey(String)}.
     */
    public String key() {
        return key;
    }

    /** 1..3 for a dozen or column (column 1 = 1, 4, ... 34; dozen 1 = 1-12), 0 for every other spot. */
    public int index() {
        return index;
    }

    /** Covered pockets, ascending by id (00 last). */
    public List<Pocket> pockets() {
        return pockets;
    }

    public Set<Pocket> pocketSet() {
        return pocketSet;
    }

    public boolean covers(Pocket pocket) {
        return pocketSet.contains(pocket);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BetSpot spot && spot.key.equals(key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return key;
    }
}
