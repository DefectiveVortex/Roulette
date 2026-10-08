package com.vortex.roulette.command;

import com.vortex.roulette.RoulettePlugin;
import com.vortex.roulette.config.ConfigManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

/** {@code /roulette}. Table and stats subcommands arrive with the parts that own them. */
public final class RouletteCommand implements TabExecutor {
    private static final String ADMIN = "roulette.admin";

    private final RoulettePlugin plugin;

    public RouletteCommand(RoulettePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ConfigManager config = plugin.config();
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> {
                sender.sendMessage(config.message("help-header"));
                config.messageList("help-lines").forEach(sender::sendMessage);
                if (sender.hasPermission(ADMIN)) {
                    config.messageList("help-admin-lines").forEach(sender::sendMessage);
                }
            }
            case "version" -> plugin.updates().sendStatus(sender);
            case "update" -> {
                if (!sender.hasPermission(ADMIN)) {
                    sender.sendMessage(config.prefixed("no-permission"));
                    return true;
                }
                plugin.updates().checkNow(sender);
            }
            case "reload" -> {
                if (!sender.hasPermission(ADMIN)) {
                    sender.sendMessage(config.prefixed("no-permission"));
                    return true;
                }
                int warnings = plugin.reload();
                sender.sendMessage(warnings == 0
                    ? config.prefixed("reload-done")
                    : config.prefixed("reload-warnings", "count", warnings));
            }
            default -> sender.sendMessage(config.prefixed("unknown-command"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> options = new ArrayList<>(List.of("help", "version"));
        if (sender.hasPermission(ADMIN)) {
            options.add("reload");
            options.add("update");
        }
        String typed = args[0].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(typed)).toList();
    }
}
