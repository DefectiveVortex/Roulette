package com.vortex.roulette.stats;

import com.vortex.roulette.RoulettePlugin;
import java.util.logging.Level;
import org.bukkit.Bukkit;

/** Registers the PlaceholderAPI expansion when PlaceholderAPI is there, and is harmless when it is not. */
public final class Placeholders {
    private Placeholders() {
    }

    /** @return true if the {@code %roulette_...%} placeholders are now available */
    public static boolean hook(RoulettePlugin plugin, StatsManager stats) {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return false;
        }
        try {
            // Named only here, so a server without PlaceholderAPI never loads a class that extends its API.
            boolean registered = new RouletteExpansion(plugin, stats).register();
            if (registered) {
                plugin.getLogger().info("PlaceholderAPI found: %roulette_...% placeholders registered.");
            }
            return registered;
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.WARNING, "Could not register the PlaceholderAPI placeholders", e);
            return false;
        }
    }
}
