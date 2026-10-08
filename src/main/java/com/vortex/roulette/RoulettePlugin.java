package com.vortex.roulette;

import com.vortex.roulette.command.RouletteCommand;
import com.vortex.roulette.config.ConfigManager;
import com.vortex.roulette.economy.Bank;
import com.vortex.roulette.game.GameClock;
import com.vortex.roulette.game.PocketSource;
import com.vortex.roulette.game.Round;
import com.vortex.roulette.game.TableRules;
import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class RoulettePlugin extends JavaPlugin {
    private final PocketSource pockets = PocketSource.secure();
    private GameClock clock;
    private ConfigManager config;
    private Bank bank;

    @Override
    public void onEnable() {
        config = new ConfigManager(this);
        clock = new BukkitClock(this);

        PluginCommand command = Objects.requireNonNull(getCommand("roulette"), "roulette command missing from plugin.yml");
        RouletteCommand executor = new RouletteCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    @Override
    public void onDisable() {
        // Tables abort their rounds here (Round#abort refunds every unpaid stake) once table/ lands.
    }

    public ConfigManager config() {
        return config;
    }

    /** Re-reads config.yml and the messages; returns how many warnings the check logged. */
    public int reload() {
        return config.reload();
    }

    /** The economy behind every table. Null until economy/ installs one with {@link #setBank}. */
    public Bank bank() {
        return bank;
    }

    public void setBank(Bank bank) {
        this.bank = bank;
    }

    /** A round machine for one table, wired to the server clock, the bank and a secure random result. */
    public Round newRound(String tableId, TableRules rules) {
        return new Round(tableId, rules, Objects.requireNonNull(bank, "no bank installed"), clock, pockets);
    }
}
