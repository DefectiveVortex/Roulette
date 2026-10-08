package com.vortex.roulette.config;

import com.vortex.roulette.util.SafeYaml;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps the admin's YAML files (config.yml, messages*.yml) loadable and current across plugin updates,
 * every time the plugin starts or reloads:
 * <ol>
 *   <li>a missing file is recreated byte for byte from the bundled default;</li>
 *   <li>a file that isn't valid YAML is moved to {@code <name>.broken-<timestamp>} (never deleted) and
 *       rebuilt from the defaults plus every value that still parses;</li>
 *   <li>ordered migrations bring an older {@code config-version} up to date, and a known key found at the
 *       wrong level (e.g. top-level {@code chat-buttons}) is moved to where it belongs;</li>
 *   <li>missing keys are added from the defaults. The file is rewritten in the bundled order with the
 *       admin's values and comments; keys we don't know stay in their section, with a warning.</li>
 * </ol>
 * Whenever it rewrites a readable file, the previous one is kept as {@code <name>.pre-update.bak}. Values of
 * the wrong type or out of range are not rewritten: {@link ConfigValidator} swaps in the default in memory.
 */
public final class ConfigFileUpdater {
    public static final String VERSION_KEY = "config-version";
    /** config-version written by this build. A file without the key is pre-1.0 (version 0). */
    public static final int CURRENT_VERSION = 1;

    /** One step in a file's history: run on files older than {@code toVersion}. Returns how many keys it changed. */
    public record Migration(int toVersion, String description, ToIntFunction<ConfigurationSection> apply) {
    }

    /**
     * @param config     the repaired config to use (all bundled keys present when there are defaults)
     * @param brokenCopy where an unparseable file was moved, or null
     * @param saved      whether the file on disk was (re)written
     * @param unknownKeys keys in the file that the defaults don't have (left in place)
     */
    public record Result(YamlConfiguration config, boolean created, File brokenCopy, int salvaged, boolean saved,
                         int migrated, int moved, int added, List<String> unknownKeys) {
    }

    private ConfigFileUpdater() {
    }

    /** Repair and update a file that ships with the plugin; returns the config to use. */
    public static YamlConfiguration update(JavaPlugin plugin, String resourceName, File targetFile) {
        return update(targetFile, bundledText(plugin, resourceName), plugin.getLogger(),
            ConfigMigrations.forFile(resourceName), ConfigMigrations.optionalKeys(resourceName)).config();
    }

    /** The bundled copy of a resource as text, or null if the jar doesn't have it. */
    public static String bundledText(JavaPlugin plugin, String resourceName) {
        try (InputStream in = plugin.getResource(resourceName)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read bundled " + resourceName + ".", e);
            return null;
        }
    }

    /**
     * The whole repair path, without a server: tests call this directly.
     *
     * @param bundled      the bundled default file's text, or null for a file with no bundled copy (a custom
     *                     language): then it's only protected against being unreadable
     * @param optionalKeys keys that may be added by hand but aren't in the bundled file (no warning)
     */
    public static Result update(File target, String bundled, Logger log, List<Migration> migrations,
                                Set<String> optionalKeys) {
        String name = target.getName();
        YamlConfiguration defaults = bundled == null ? null : parse(bundled, name, log);

        if (!target.exists()) {
            if (defaults == null) {
                return new Result(new YamlConfiguration(), false, null, 0, false, 0, 0, 0, List.of());
            }
            try {
                SafeYaml.writeAtomically(target, bundled.getBytes(StandardCharsets.UTF_8));
                log.info("Created " + name + " from bundled defaults.");
            } catch (IOException e) {
                log.log(Level.SEVERE, "Could not create " + name + "; using the bundled defaults.", e);
            }
            return new Result(parse(bundled, name, log), true, null, 0, true, 0, 0, 0, List.of());
        }

        SafeYaml.LoadResult loaded = SafeYaml.load(target, log, false);
        YamlConfiguration user = loaded.config();
        File brokenCopy = null;
        int salvaged = 0;
        if (loaded.status() == SafeYaml.Status.BROKEN) {
            if (loaded.brokenCopy() == null) {
                log.warning(name + " could not be read (" + loaded.error() + ") and was left untouched. "
                    + (defaults == null ? "It is ignored" : "Using the defaults") + " until it's fixed.");
                YamlConfiguration fallback = defaults == null ? new YamlConfiguration() : parse(bundled, name, log);
                return new Result(fallback, false, null, 0, false, 0, 0, 0, List.of());
            }
            brokenCopy = loaded.brokenCopy();
            user = SafeYaml.salvage(brokenCopy);
            salvaged = SafeYaml.countValues(user);
        }

        // Migrations: ordered steps from the file's version to ours.
        Object storedVersion = user.get(VERSION_KEY);
        int version = storedVersion instanceof Number n ? n.intValue() : 0;
        int migrated = 0;
        if (version > CURRENT_VERSION) {
            log.warning(name + " has " + VERSION_KEY + " " + version + ", newer than this Roulette build ("
                + CURRENT_VERSION + "). Loading it as it is; downgrades are not migrated.");
        } else {
            for (Migration m : migrations) {
                if (m.toVersion() > version) {
                    int changed = m.apply().applyAsInt(user);
                    if (changed > 0) {
                        log.info(name + ": " + m.description());
                        migrated += changed;
                    }
                }
            }
        }

        int moved = 0;
        List<String> unknown = new ArrayList<>();
        YamlConfiguration out;
        int added = 0;
        if (defaults == null) {
            out = user;
        } else {
            moved = relocateMisplaced(user, defaults, optionalKeys, name, log);
            out = parse(bundled, name, log);
            for (String path : leaves(defaults)) {
                if (!path.equals(VERSION_KEY) && !user.contains(path)) {
                    added++;
                }
            }
            overlay(user, out, name, log);
            unknown = unknownKeys(user, defaults, optionalKeys);
        }
        int newVersion = Math.max(version, CURRENT_VERSION);
        boolean versionChanged = !(storedVersion instanceof Number n2 && n2.intValue() == newVersion);
        if (defaults != null || versionChanged) {
            out.set(VERSION_KEY, newVersion);
        }

        boolean saved = false;
        if (brokenCopy != null || migrated > 0 || moved > 0 || added > 0 || versionChanged) {
            saved = save(target, out, brokenCopy == null, log);
            if (brokenCopy != null) {
                log.warning(name + " could not be read (" + loaded.error() + "). Moved it to " + brokenCopy.getName()
                    + " and rebuilt it from " + (defaults == null ? "what could still be read" : "the defaults")
                    + ", keeping " + salvaged + " value(s) that could still be read.");
            } else if (saved) {
                List<String> parts = new ArrayList<>();
                if (versionChanged) parts.add(VERSION_KEY + " " + version + " -> " + newVersion);
                if (migrated > 0) parts.add(migrated + " key(s) migrated");
                if (moved > 0) parts.add(moved + " key(s) moved");
                if (added > 0) parts.add(added + " missing key(s) added");
                log.info("Updated " + name + ": " + String.join(", ", parts)
                    + ". Your values were kept. Backup: " + backupName(name));
            }
        }
        if (!unknown.isEmpty()) {
            log.warning(name + ": unknown key(s) left as they are: " + String.join(", ", unknown));
        }
        return new Result(out, false, brokenCopy, salvaged, saved, migrated, moved, added, unknown);
    }

    static String backupName(String fileName) {
        return fileName + ".pre-update.bak";
    }

    private static boolean save(File target, YamlConfiguration out, boolean backup, Logger log) {
        try {
            if (backup && target.exists()) {
                Files.copy(target.toPath(), new File(target.getParentFile(), backupName(target.getName())).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            }
            wide(out);
            SafeYaml.saveAtomically(out, target);
            return true;
        } catch (IOException e) {
            log.log(Level.SEVERE, "Could not save the repaired " + target.getName()
                + "; using the repaired settings until the next restart.", e);
            return false;
        }
    }

    private static YamlConfiguration parse(String text, String name, Logger log) {
        YamlConfiguration yaml = new YamlConfiguration();
        wide(yaml);
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            log.log(Level.SEVERE, "The bundled " + name + " is not valid YAML. This is a bug in the Roulette jar.", e);
        }
        return yaml;
    }

    /** Don't fold long messages onto several lines when saving (the width option is 1.18.1+). */
    private static void wide(YamlConfiguration yaml) {
        try {
            yaml.options().width(Integer.MAX_VALUE / 2);
        } catch (NoSuchMethodError | RuntimeException ignored) {
            // older API: SnakeYAML's default width, still valid YAML
        }
    }

    /** Paths of values (not sections) in a config. */
    static List<String> leaves(ConfigurationSection section) {
        List<String> out = new ArrayList<>();
        for (String key : section.getKeys(true)) {
            if (!section.isConfigurationSection(key)) {
                out.add(key);
            }
        }
        return out;
    }

    /**
     * Put every value from the admin's file onto the freshly parsed defaults, so the result has the bundled
     * order and comments, the admin's values, the admin's own comments, and unknown keys at the end of their
     * section. A value where the defaults have a whole section can't be kept (it would wipe the section).
     */
    private static void overlay(YamlConfiguration user, YamlConfiguration out, String name, Logger log) {
        for (String path : user.getKeys(true)) {
            if (path.equals(VERSION_KEY)) {
                continue;
            }
            if (user.isConfigurationSection(path)) {
                if (out.contains(path) && !out.isConfigurationSection(path)) {
                    out.set(path, null);
                }
                if (!out.isConfigurationSection(path)) {
                    out.createSection(path);
                }
            } else if (out.isConfigurationSection(path)) {
                log.warning(name + ": " + path + " should be a section, not a single value (" + user.get(path)
                    + "); using the default section.");
                continue;
            } else if (!isUnderValue(out, path)) {
                out.set(path, user.get(path));
            }
            copyComments(user, out, path);
        }
    }

    /** True when a parent of {@code path} is a plain value in {@code out} (set() would replace it with a section). */
    private static boolean isUnderValue(ConfigurationSection out, String path) {
        int dot = path.lastIndexOf('.');
        return dot > 0 && out.contains(path.substring(0, dot)) && !out.isConfigurationSection(path.substring(0, dot));
    }

    private static void copyComments(ConfigurationSection from, ConfigurationSection to, String path) {
        copyComments(from, to, path, path);
    }

    private static void copyComments(ConfigurationSection from, ConfigurationSection to, String fromPath, String toPath) {
        try {
            List<String> comments = from.getComments(fromPath);
            if (!comments.isEmpty() && !comments.equals(to.getComments(toPath))) {
                to.setComments(toPath, comments);
            }
            List<String> inline = from.getInlineComments(fromPath);
            if (!inline.isEmpty() && !inline.equals(to.getInlineComments(toPath))) {
                to.setInlineComments(toPath, inline);
            }
        } catch (NoSuchMethodError ignored) {
            // no comment API on this server: values are still kept
        }
    }

    /**
     * A known key placed at the wrong level (say top-level {@code chat-buttons} instead of
     * {@code interface.chat-buttons}) is moved where it belongs, when its name is unique among the known keys,
     * the right place isn't set already, and the value has the right kind.
     */
    static int relocateMisplaced(YamlConfiguration user, YamlConfiguration defaults, Set<String> optionalKeys,
                                 String name, Logger log) {
        Map<String, List<String>> byLeafName = new HashMap<>();
        for (String path : leaves(defaults)) {
            byLeafName.computeIfAbsent(lastSegment(path), k -> new ArrayList<>()).add(path);
        }
        int moved = 0;
        for (String path : leaves(user)) {
            if (path.equals(VERSION_KEY) || defaults.contains(path) || optionalKeys.contains(path)) {
                continue;
            }
            List<String> candidates = byLeafName.get(lastSegment(path));
            if (candidates == null || candidates.size() != 1) {
                continue;
            }
            String target = candidates.get(0);
            if (user.contains(target) || !sameKind(user.get(path), defaults.get(target))) {
                continue;
            }
            user.set(target, user.get(path));
            moveComments(user, path, target);
            user.set(path, null);
            removeEmptyParents(user, path);
            log.info(name + ": moved " + path + " to " + target + ".");
            moved++;
        }
        return moved;
    }

    /** Move a value to a new key (a rename); used by migrations. Returns 1 if something moved. */
    public static int move(ConfigurationSection config, String from, String to) {
        if (!config.contains(from) || config.isConfigurationSection(from)) {
            return 0;
        }
        if (!config.contains(to)) {
            config.set(to, config.get(from));
            moveComments(config, from, to);
        }
        config.set(from, null);
        removeEmptyParents(config, from);
        return 1;
    }

    /**
     * Replace a message that still has an old bundled text (the admin never customised it) with the new
     * text. A customised message is left alone. Returns 1 if it changed.
     */
    public static int replaceOldDefault(ConfigurationSection config, String path, String newValue, String... oldDefaults) {
        Object current = config.get(path);
        for (String old : oldDefaults) {
            if (Objects.equals(current, old)) {
                config.set(path, newValue);
                return 1;
            }
        }
        return 0;
    }

    /** The admin's comments above and beside a key go with it when it moves. */
    private static void moveComments(ConfigurationSection config, String from, String to) {
        copyComments(config, config, from, to);
    }

    private static void removeEmptyParents(ConfigurationSection config, String path) {
        for (int dot = path.lastIndexOf('.'); dot > 0; dot = path.lastIndexOf('.', dot - 1)) {
            String parent = path.substring(0, dot);
            ConfigurationSection s = config.getConfigurationSection(parent);
            if (s == null || !s.getKeys(false).isEmpty()) {
                return;
            }
            config.set(parent, null);
        }
    }

    private static boolean sameKind(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) return true;
        if (a instanceof Boolean && b instanceof Boolean) return true;
        if (a instanceof List && b instanceof List) return true;
        return a instanceof String && b instanceof String;
    }

    private static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }

    /** The outermost keys in the admin's file that the defaults don't know. */
    private static List<String> unknownKeys(YamlConfiguration user, YamlConfiguration defaults, Set<String> optionalKeys) {
        Set<String> unknown = new LinkedHashSet<>();
        for (String path : user.getKeys(true)) {
            if (path.equals(VERSION_KEY) || defaults.contains(path) || optionalKeys.contains(path)
                || optionalKeys.stream().anyMatch(o -> o.startsWith(path + "."))) {
                continue;
            }
            int dot = path.lastIndexOf('.');
            if (dot > 0 && unknown.stream().anyMatch(u -> path.startsWith(u + "."))) {
                continue;
            }
            unknown.add(path);
        }
        return new ArrayList<>(unknown);
    }
}
