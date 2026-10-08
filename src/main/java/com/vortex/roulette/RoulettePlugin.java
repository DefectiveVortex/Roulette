package com.vortex.roulette;

import com.vortex.roulette.command.RouletteCommand;
import com.vortex.roulette.config.ConfigManager;
import com.vortex.roulette.economy.Bank;
import com.vortex.roulette.economy.Money;
import com.vortex.roulette.game.GameClock;
import com.vortex.roulette.game.PocketSource;
import com.vortex.roulette.game.Round;
import com.vortex.roulette.game.TableRules;
import com.vortex.roulette.pack.PackDelivery;
import com.vortex.roulette.stats.Placeholders;
import com.vortex.roulette.stats.StatsCommand;
import com.vortex.roulette.stats.StatsManager;
import com.vortex.roulette.update.UpdateService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class RoulettePlugin extends JavaPlugin {
    private final PocketSource pockets = PocketSource.secure();
    private GameClock clock;
    private ConfigManager config;
    private UpdateService updates;
    private Money money;
    private StatsManager stats;
    private PackDelivery pack;
    /** Every round machine handed out and not yet discarded, so a disable can refund what is on the tables. */
    private final Set<Round> rounds = new LinkedHashSet<>();
    private Bank bank;

    @Override
    public void onEnable() {
        config = new ConfigManager(this);
        clock = new BukkitClock(this);
        money = Money.install(this);
        stats = new StatsManager(this, config.statsSaveMinutes());
        Placeholders.hook(this, stats);
        pack = new PackDelivery(this);
        getServer().getPluginManager().registerEvents(pack, this);

        PluginCommand command = Objects.requireNonNull(getCommand("roulette"), "roulette command missing from plugin.yml");
        RouletteCommand executor = new RouletteCommand(this, new StatsCommand(config, stats));
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        updates = new UpdateService(this);
        updates.start();
    }

    @Override
    public void onDisable() {
        if (updates != null) {
            updates.stop();
        }
        // Order matters: refunds go through the bank, so rounds first and the journal last.
        for (Round round : new ArrayList<>(rounds)) {
            round.abort();
        }
        rounds.clear();
        if (stats != null) {
            stats.shutdown();
        }
        if (money != null) {
            money.shutdown();
        }
    }

    public ConfigManager config() {
        return config;
    }

    /** Re-reads config.yml and the messages; returns how many warnings the check logged. */
    public int reload() {
        int warnings = config.reload();
        updates.reload();
        return warnings;
    }

    public UpdateService updates() {
        return updates;
    }

    /** The economy behind every table. Null until economy/ installs one with {@link #setBank}. */
    public Bank bank() {
        return bank;
    }

    public void setBank(Bank bank) {
        this.bank = bank;
    }

    /**
     * A round machine for one table, wired to the server clock, the bank, a secure random result and the
     * statistics. Whoever takes one hands it back with {@link #discardRound} when its table goes away; rounds still
     * out at disable are aborted, which refunds every unpaid stake.
     */
    public Round newRound(String tableId, TableRules rules) {
        Round round = new Round(tableId, rules, Objects.requireNonNull(bank, "no bank installed"), clock, pockets);
        round.addListener(stats);
        rounds.add(round);
        return round;
    }

    /** Ends a round machine for good (its table was removed or rebuilt): refunds what is on it and forgets it. */
    public void discardRound(Round round) {
        round.abort();
        rounds.remove(round);
    }

    /** Offers the resource pack and knows who has it. */
    public PackDelivery pack() {
        return pack;
    }

    public StatsManager stats() {
        return stats;
    }
}
