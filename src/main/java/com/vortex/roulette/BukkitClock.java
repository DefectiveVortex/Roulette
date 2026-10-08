package com.vortex.roulette;

import com.vortex.roulette.game.GameClock;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** The server's tick loop as a {@link GameClock}. */
final class BukkitClock implements GameClock {
    private final Plugin plugin;

    BukkitClock(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Task runLater(long delayTicks, Runnable action) {
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, action, delayTicks);
        return task::cancel;
    }

    @Override
    public long now() {
        return Bukkit.getCurrentTick();
    }
}
