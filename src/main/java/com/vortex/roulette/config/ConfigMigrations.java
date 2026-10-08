package com.vortex.roulette.config;

import com.vortex.roulette.config.ConfigFileUpdater.Migration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The history of each bundled file, oldest first. To rename or move a key in a release: bump
 * {@link ConfigFileUpdater#CURRENT_VERSION}, add a {@link Migration} here with that number, and add the old
 * default texts to defaults-history.yml. Migrations only touch the admin's file; missing keys are added
 * afterwards from the bundled defaults.
 */
public final class ConfigMigrations {
    private ConfigMigrations() {
    }

    /** Ordered migrations for a bundled file ("config.yml", "messages_ko.yml", ...). */
    public static List<Migration> forFile(String resourceName) {
        List<Migration> list = new ArrayList<>();
        List<Map<?, ?>> history = history(resourceName);
        if (!history.isEmpty()) {
            // Not tied to a version: a value that still equals an old bundled default was never customised.
            list.add(new Migration(Integer.MAX_VALUE, "updated text(s) that still had an older bundled default",
                c -> applyHistory(c, history)));
        }
        return list;
    }

    /** Keys an admin may add that the bundled file leaves out on purpose: kept without a warning. */
    public static Set<String> optionalKeys(String resourceName) {
        if (resourceName.equals("config.yml")) {
            return Set.of("updates.api-url");
        }
        return Set.of();
    }

    /**
     * Drop every value that still equals an older bundled default, so the current default is filled in. A key
     * that no longer exists is dropped only when it holds its old default; a customised one stays (and is
     * reported as unknown).
     */
    static int applyHistory(ConfigurationSection config, List<Map<?, ?>> history) {
        int changed = 0;
        for (Map<?, ?> entry : history) {
            Object key = entry.get("key");
            Object old = entry.get("old");
            if (!(key instanceof String path) || !(old instanceof List<?> olds) || config.isConfigurationSection(path)) {
                continue;
            }
            Object current = config.get(path);
            if (current != null && olds.stream().anyMatch(o -> Objects.equals(o, current))) {
                config.set(path, null);
                changed++;
            }
        }
        return changed;
    }

    /** Entries of defaults-history.yml for one file (keyed by name without ".yml"). */
    static List<Map<?, ?>> history(String resourceName) {
        String section = resourceName.endsWith(".yml") ? resourceName.substring(0, resourceName.length() - 4) : resourceName;
        try (InputStream in = ConfigMigrations.class.getClassLoader().getResourceAsStream("defaults-history.yml")) {
            if (in == null) {
                return List.of();
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml.getMapList(section);
        } catch (IOException e) {
            return List.of();
        }
    }
}
