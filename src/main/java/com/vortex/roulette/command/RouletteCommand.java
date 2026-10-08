package com.vortex.roulette.command;

import com.vortex.roulette.RoulettePlugin;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

/** {@code /roulette}. Subcommands arrive with the parts that need them. */
public final class RouletteCommand implements TabExecutor {
    private final RoulettePlugin plugin;

    public RouletteCommand(RoulettePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendMessage("Roulette " + plugin.getPluginMeta().getVersion());
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return List.of();
    }
}
