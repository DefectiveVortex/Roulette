package com.vortex.roulette.update;

import java.util.ArrayList;
import java.util.List;

/**
 * Orders version numbers such as "1.0", "1.0.1", "v1.2.0" and "1.1-beta.2". The numeric core compares
 * number by number (missing parts count as 0, so "1.0" equals "1.0.0"); a pre-release suffix after '-'
 * sorts below the same core without one; build metadata after '+' is ignored.
 */
public final class Versions {
    private Versions() {
    }

    /** Negative, zero or positive as {@code a} is older than, the same as or newer than {@code b}. */
    public static int compare(String a, String b) {
        Parsed x = parse(a), y = parse(b);
        int n = Math.max(x.core.size(), y.core.size());
        for (int i = 0; i < n; i++) {
            int c = Long.compare(i < x.core.size() ? x.core.get(i) : 0, i < y.core.size() ? y.core.get(i) : 0);
            if (c != 0) return c;
        }
        if (x.pre.isEmpty() != y.pre.isEmpty()) return x.pre.isEmpty() ? 1 : -1;
        for (int i = 0; i < Math.min(x.pre.size(), y.pre.size()); i++) {
            int c = comparePart(x.pre.get(i), y.pre.get(i));
            if (c != 0) return c;
        }
        return Integer.compare(x.pre.size(), y.pre.size());
    }

    public static boolean isNewer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    private record Parsed(List<Long> core, List<String> pre) {}

    private static Parsed parse(String version) {
        String v = version == null ? "" : version.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        int plus = v.indexOf('+');
        if (plus >= 0) v = v.substring(0, plus);
        int dash = v.indexOf('-');
        String core = dash >= 0 ? v.substring(0, dash) : v;
        String pre = dash >= 0 ? v.substring(dash + 1) : "";

        List<Long> numbers = new ArrayList<>();
        for (String part : core.split("\\.")) {
            int end = 0;
            while (end < part.length() && Character.isDigit(part.charAt(end))) end++;
            numbers.add(end == 0 ? 0 : parseLong(part.substring(0, end)));
            if (end < part.length()) {
                // "1.0beta" style: the trailing letters start the pre-release
                pre = part.substring(end) + (pre.isEmpty() ? "" : "." + pre);
                break;
            }
        }
        while (numbers.size() > 1 && numbers.get(numbers.size() - 1) == 0) numbers.remove(numbers.size() - 1);

        List<String> parts = new ArrayList<>();
        // split "beta.2", "beta2" and "rc-1" alike into letter runs and digit runs
        for (String piece : pre.toLowerCase().split("[.\\-_]")) {
            int i = 0;
            while (i < piece.length()) {
                boolean digit = Character.isDigit(piece.charAt(i));
                int j = i;
                while (j < piece.length() && Character.isDigit(piece.charAt(j)) == digit) j++;
                parts.add(piece.substring(i, j));
                i = j;
            }
        }
        return new Parsed(numbers, parts);
    }

    private static int comparePart(String a, String b) {
        boolean na = !a.isEmpty() && a.chars().allMatch(Character::isDigit);
        boolean nb = !b.isEmpty() && b.chars().allMatch(Character::isDigit);
        if (na && nb) return Long.compare(parseLong(a), parseLong(b));
        if (na != nb) return na ? -1 : 1; // numbers sort below words, as in semver
        return a.compareTo(b);
    }

    private static long parseLong(String digits) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }
}
