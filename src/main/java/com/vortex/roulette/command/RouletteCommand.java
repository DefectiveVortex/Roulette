package com.vortex.roulette.command;

import com.vortex.roulette.RoulettePlugin;
import com.vortex.roulette.config.ConfigManager;
import com.vortex.roulette.stats.StatsCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

/** {@code /roulette}. Table and stats subcommands arrive with the parts that own them. */
public final class RouletteCommand implements TabExecutor {
    private static final String ADMIN = "roulette.admin";

    private static final String PLAY = "roulette.play";
    private static final String STATS_OTHERS = "roulette.stats.others";

    private final RoulettePlugin plugin;
    private final StatsCommand stats;

    public RouletteCommand(RoulettePlugin plugin, StatsCommand stats) {
        this.plugin = plugin;
        this.stats = stats;
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
            default -> {
                if (!StatsCommand.subcommands().contains(sub)) {
                    sender.sendMessage(config.prefixed("unknown-command"));
                } else if (!sender.hasPermission(PLAY) || (sub.equals("stats") && args.length > 1
                        && !args[1].equalsIgnoreCase(sender.getName()) && !sender.hasPermission(STATS_OTHERS))) {
                    sender.sendMessage(config.prefixed("no-permission"));
                } else if (!stats.handle(sender, args)) {
                    sender.sendMessage(config.prefixed("unknown-command"));
                }
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return List.of();
        }
        if (args.length > 1) {
            boolean own = StatsCommand.subcommands().contains(args[0].toLowerCase(Locale.ROOT));
            boolean names = args[0].equalsIgnoreCase("stats") && !sender.hasPermission(STATS_OTHERS);
            return own && !names && sender.hasPermission(PLAY) ? stats.complete(sender, args) : List.of();
        }
        List<String> options = new ArrayList<>(List.of("help", "version"));
        if (sender.hasPermission(PLAY)) {
            options.addAll(StatsCommand.subcommands());
        }
        if (sender.hasPermission(ADMIN)) {
            options.add("reload");
            options.add("update");
        }
        String typed = args[0].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(typed)).toList();
    }
}
