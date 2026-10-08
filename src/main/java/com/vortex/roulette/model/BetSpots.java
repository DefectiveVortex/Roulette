package com.vortex.roulette.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;

/** Every bet spot that exists on one wheel's felt. Immutable; one shared instance per wheel via {@link #of}. */
public final class BetSpots {
    private static final Map<WheelType, BetSpots> BY_WHEEL = new EnumMap<>(WheelType.class);

    static {
        for (WheelType wheel : WheelType.values()) {
            BY_WHEEL.put(wheel, new BetSpots(wheel));
        }
    }

    private final WheelType wheel;
    private final Map<String, BetSpot> byKey = new LinkedHashMap<>();
    private final Map<Set<Pocket>, BetSpot> byPockets = new HashMap<>();

    public static BetSpots of(WheelType wheel) {
        return BY_WHEEL.get(wheel);
    }

    private BetSpots(WheelType wheel) {
        this.wheel = wheel;
        boolean american = wheel.hasDoubleZero();

        for (Pocket pocket : sorted(wheel.pockets())) {
            inside(BetType.STRAIGHT, pocket.id());
        }
        // Splits: the zero pockets first, then across a row (n, n+1) and along a column (n, n+3).
        if (american) {
            inside(BetType.SPLIT, 0, Pocket.DOUBLE_ZERO_ID);
            inside(BetType.SPLIT, 0, 1);
            inside(BetType.SPLIT, 0, 2);
            inside(BetType.SPLIT, 2, Pocket.DOUBLE_ZERO_ID);
            inside(BetType.SPLIT, 3, Pocket.DOUBLE_ZERO_ID);
        } else {
            inside(BetType.SPLIT, 0, 1);
            inside(BetType.SPLIT, 0, 2);
            inside(BetType.SPLIT, 0, 3);
        }
        for (int n = 1; n <= 36; n++) {
            if (n % 3 != 0) {
                inside(BetType.SPLIT, n, n + 1);
            }
            if (n + 3 <= 36) {
                inside(BetType.SPLIT, n, n + 3);
            }
        }
        for (int n = 1; n <= 34; n += 3) {
            inside(BetType.STREET, n, n + 1, n + 2);
        }
        if (american) {
            inside(BetType.TRIO, 0, 1, 2);
            inside(BetType.TRIO, 0, 2, Pocket.DOUBLE_ZERO_ID);
            inside(BetType.TRIO, 2, 3, Pocket.DOUBLE_ZERO_ID);
            inside(BetType.TOP_LINE, 0, 1, 2, 3, Pocket.DOUBLE_ZERO_ID);
        } else {
            inside(BetType.TRIO, 0, 1, 2);
            inside(BetType.TRIO, 0, 2, 3);
            inside(BetType.FIRST_FOUR, 0, 1, 2, 3);
        }
        for (int n = 1; n <= 32; n++) {
            if (n % 3 != 0) {
                inside(BetType.CORNER, n, n + 1, n + 3, n + 4);
            }
        }
        for (int n = 1; n <= 31; n += 3) {
            inside(BetType.SIX_LINE, n, n + 1, n + 2, n + 3, n + 4, n + 5);
        }
        for (int i = 1; i <= 3; i++) {
            int index = i;
            outside(BetType.DOZEN, "dozen:" + i, i, n -> Pocket.of(n).dozen() == index);
        }
        for (int i = 1; i <= 3; i++) {
            int index = i;
            outside(BetType.COLUMN, "column:" + i, i, n -> Pocket.of(n).column() == index);
        }
        outside(BetType.RED, "red", 0, n -> Pocket.of(n).color() == PocketColor.RED);
        outside(BetType.BLACK, "black", 0, n -> Pocket.of(n).color() == PocketColor.BLACK);
        outside(BetType.ODD, "odd", 0, n -> n % 2 == 1);
        outside(BetType.EVEN, "even", 0, n -> n % 2 == 0);
        outside(BetType.LOW, "low", 0, n -> n <= 18);
        outside(BetType.HIGH, "high", 0, n -> n >= 19);
    }

    public WheelType wheel() {
        return wheel;
    }

    /** All spots in a fixed order: inside bets by type, then dozens, columns and the even-money bets. */
    public Collection<BetSpot> all() {
        return java.util.Collections.unmodifiableCollection(byKey.values());
    }

    public Optional<BetSpot> byKey(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /** The spot covering exactly these pockets, if this wheel has one. This is how felt geometry finds its spot. */
    public Optional<BetSpot> covering(Collection<Pocket> pockets) {
        return Optional.ofNullable(byPockets.get(Set.copyOf(pockets)));
    }

    public BetSpot straight(Pocket pocket) {
        return require("straight:" + pocket.label());
    }

    /** @param index 1..3 */
    public BetSpot dozen(int index) {
        return require("dozen:" + index);
    }

    /** @param index 1..3 */
    public BetSpot column(int index) {
        return require("column:" + index);
    }

    /** The single spot of an even-money type (RED, BLACK, ODD, EVEN, LOW, HIGH). */
    public BetSpot evenMoney(BetType type) {
        return require(type.name().toLowerCase(Locale.ROOT));
    }

    public boolean contains(BetSpot spot) {
        return spot != null && spot == byKey.get(spot.key());
    }

    private BetSpot require(String key) {
        BetSpot spot = byKey.get(key);
        if (spot == null) {
            throw new IllegalArgumentException("no bet spot " + key + " on the " + wheel + " wheel");
        }
        return spot;
    }

    private void inside(BetType type, int... ids) {
        List<Pocket> pockets = new ArrayList<>();
        for (int id : ids) {
            pockets.add(Pocket.of(id));
        }
        pockets = sorted(pockets);
        String key = type.name().toLowerCase(Locale.ROOT).replace("_", "") + ":"
                + pockets.stream().map(Pocket::label).collect(Collectors.joining("-"));
        add(new BetSpot(type, key, 0, pockets));
    }

    private void outside(BetType type, String key, int index, IntPredicate number) {
        List<Pocket> pockets = new ArrayList<>();
        for (int n = 1; n <= 36; n++) {
            if (number.test(n)) {
                pockets.add(Pocket.of(n));
            }
        }
        add(new BetSpot(type, key, index, pockets));
    }

    private void add(BetSpot spot) {
        if (byKey.put(spot.key(), spot) != null || byPockets.put(spot.pocketSet(), spot) != null) {
            throw new IllegalStateException("duplicate bet spot " + spot.key());
        }
    }

    private static List<Pocket> sorted(Collection<Pocket> pockets) {
        return pockets.stream().sorted().toList();
    }
}
