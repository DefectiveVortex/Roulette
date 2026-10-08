package com.vortex.roulette.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Checks values after a file is loaded. A value of the wrong type or out of range is replaced by its default
 * in memory only (the file keeps what the admin wrote) and a warning names the key, the value and what's
 * used instead: {@code config.yml: table.big-blind must be a whole number, got "lots"; using 20.}
 * Quoted numbers and booleans ("30", "true") are read as what they obviously mean.
 */
public final class ConfigValidator {
    private static final Pattern COLOR_CODES = Pattern.compile("^((&|§)([0-9a-fk-orA-FK-OR]|#[0-9a-fA-F]{6}))*$");
    private static final Pattern SHA1 = Pattern.compile("^([0-9a-fA-F]{40})?$");

    private final ConfigurationSection config;
    private final ConfigurationSection defaults;
    private final String file;
    private final Logger log;
    private int problems;

    private ConfigValidator(ConfigurationSection config, ConfigurationSection defaults, String file, Logger log) {
        this.config = config;
        this.defaults = defaults;
        this.file = file;
        this.log = log;
    }

    /** Type checks against the defaults for any file; returns the number of values replaced. */
    public static int validateTypes(ConfigurationSection config, ConfigurationSection defaults, String file, Logger log) {
        ConfigValidator v = new ConfigValidator(config, defaults, file, log);
        v.types();
        return v.problems;
    }

    /** Type checks plus the ranges and cross-checks of config.yml; returns the number of values replaced. */
    public static int validateConfig(ConfigurationSection config, ConfigurationSection defaults, Logger log) {
        ConfigValidator v = new ConfigValidator(config, defaults, "config.yml", log);
        v.types();
        v.configRanges();
        return v.problems;
    }

    // ---------- types ----------

    private void types() {
        for (String path : ConfigFileUpdater.leaves(defaults)) {
            if (path.equals(ConfigFileUpdater.VERSION_KEY) || !config.contains(path)) {
                continue;
            }
            Object def = defaults.get(path);
            Object val = config.get(path);
            if (config.isConfigurationSection(path)) {
                reject(path, "must be a single value, not a section", "a section", def);
            } else if (def instanceof Boolean) {
                Boolean b = asBoolean(val);
                if (b == null) reject(path, "must be true or false", val, def);
                else if (!(val instanceof Boolean)) config.set(path, b);
            } else if (def instanceof Integer || def instanceof Long) {
                Long n = asWhole(val);
                if (n == null) reject(path, "must be a whole number", val, def);
                else if (!(val instanceof Integer || val instanceof Long)) config.set(path, narrow(n));
            } else if (def instanceof Number) {
                Double d = asDouble(val);
                if (d == null) reject(path, "must be a number", val, def);
                else if (!(val instanceof Number)) config.set(path, d);
            } else if (def instanceof String) {
                if (val instanceof List || val instanceof ConfigurationSection) reject(path, "must be a single line of text", val, def);
                else if (!(val instanceof String)) config.set(path, String.valueOf(val));
            } else if (def instanceof List) {
                if (val instanceof String s) config.set(path, List.of(s));
                else if (!(val instanceof List)) reject(path, "must be a list", val, def);
            }
        }
    }

    // ---------- config.yml ----------

    private void configRanges() {
        oneOf("table.wheel", Set.of("european", "american"));
        atLeast("table.min-bet", 1);
        atLeast("table.max-bet", 1);
        long min = config.getLong("table.min-bet");
        long max = config.getLong("table.max-bet");
        if (max < min) {
            replace("table.max-bet", "must not be less than table.min-bet (" + min + ")", max, narrow(min));
        }
        atLeast("table.max-payout", 0);
        range("table.betting-seconds", 5, 600);
        range("table.spin-seconds", 3, 30);
        range("table.result-seconds", 1, 60);

        range("stats.save-minutes", 1, 1440);

        range("wheel.board-numbers", 0, 30);

        oneOf("resource-pack.send", Set.of("table", "join", "never"));
        String url = config.getString("resource-pack.url", "");
        if (!url.isEmpty() && !url.startsWith("https://") && !url.startsWith("http://")) {
            replace("resource-pack.url", "must be an http(s) link or empty", url, "");
        }
        String sha1 = config.getString("resource-pack.sha1", "");
        if (!SHA1.matcher(sha1.trim()).matches()) {
            // With no hash clients skip the check and download the pack every session.
            replace("resource-pack.sha1", "must be 40 hex characters or empty", sha1, "");
        }

        oneOf("updates.channel", Set.of("release", "beta", "alpha"));
        atLeast("updates.interval-hours", 1);
    }

    private void range(String path, long min, long max) {
        if (!config.contains(path)) return;
        long v = config.getLong(path);
        if (v < min || v > max) {
            replace(path, "must be between " + min + " and " + max, v, defaults.get(path));
        }
    }

    private void atLeast(String path, long min) {
        if (!config.contains(path)) return;
        long v = config.getLong(path);
        if (v < min) {
            replace(path, "must be at least " + min, v, defaults.get(path));
        }
    }

    /** 0 turns the feature off; otherwise at least min. Below 0 means the default, 1..min-1 means min. */
    private void zeroOrAtLeast(String path, long min) {
        if (!config.contains(path)) return;
        long v = config.getLong(path);
        if (v < 0) {
            replace(path, "must be 0 (off) or at least " + min, v, defaults.get(path));
        } else if (v > 0 && v < min) {
            replace(path, "must be 0 (off) or at least " + min, v, narrow(min));
        }
    }

    private void atLeastDouble(String path, double min) {
        if (!config.contains(path)) return;
        double v = config.getDouble(path);
        if (v < min || Double.isNaN(v)) {
            replace(path, "must be at least " + min, v, defaults.get(path));
        }
    }

    private void rangeDouble(String path, double min, double max) {
        if (!config.contains(path)) return;
        double v = config.getDouble(path);
        if (!(v >= min && v <= max)) {
            Object def = defaults.contains(path) ? defaults.get(path) : Math.max(min, Math.min(max, 1.0));
            replace(path, "must be between " + min + " and " + max, v, def);
        }
    }

    private void oneOf(String path, Set<String> allowed) {
        if (!config.contains(path)) return;
        String v = config.getString(path, "").trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(v)) {
            replace(path, "must be one of " + String.join(", ", allowed.stream().sorted().toList()),
                config.get(path), defaults.get(path));
        } else if (!v.equals(config.getString(path))) {
            config.set(path, v);
        }
    }

    private void colorCodes(String path) {
        if (!config.contains(path)) return;
        String v = config.getString(path, "");
        if (!COLOR_CODES.matcher(v.trim()).matches()) {
            Object def = defaults.contains(path) ? defaults.get(path) : "&f";
            replace(path, "must be colour codes like &c or &#ff0000", v, def);
        }
    }

    private void reject(String path, String reason, Object got, Object def) {
        replace(path, reason, got, def);
    }

    private void replace(String path, String reason, Object got, Object use) {
        config.set(path, use);
        problems++;
        log.warning(file + ": " + path + " " + reason + ", got " + show(got) + "; using " + show(use) + ".");
    }

    private static String show(Object o) {
        return o instanceof String s ? "\"" + s + "\"" : String.valueOf(o);
    }

    private static Object narrow(long n) {
        return n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE ? (Object) (int) n : (Object) n;
    }

    static Boolean asBoolean(Object o) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) {
            switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "true", "yes", "on" -> { return true; }
                case "false", "no", "off" -> { return false; }
                default -> { return null; }
            }
        }
        return null;
    }

    static Long asWhole(Object o) {
        if (o instanceof Integer || o instanceof Long || o instanceof Short || o instanceof Byte) {
            return ((Number) o).longValue();
        }
        if (o instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && !Double.isInfinite(d) ? (long) d : null;
        }
        if (o instanceof String s) {
            try {
                return Long.parseLong(s.trim().replace("_", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static Double asDouble(Object o) {
        if (o instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        if (o instanceof String s) {
            try {
                double d = Double.parseDouble(s.trim());
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
