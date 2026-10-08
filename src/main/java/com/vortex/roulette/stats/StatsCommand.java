package com.vortex.roulette.stats;

import com.vortex.roulette.config.ConfigManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /roulette stats [player]} and {@code /roulette top [wagered|won|net|biggest|rounds]}. The main command
 * routes both subcommands here with the full argument array (args[0] is the subcommand).
 */
public final class StatsCommand {
    private static final int TOP_SIZE = 10;
    private static final List<String> SUBCOMMANDS = List.of("stats", "top");

    private final ConfigManager config;
    private final StatsManager stats;

    public StatsCommand(ConfigManager config, StatsManager stats) {
        this.config = config;
        this.stats = stats;
    }

    /** The subcommands this class answers. */
    public static List<String> subcommands() {
        return SUBCOMMANDS;
    }

    /** @return false if args[0] is not one of {@link #subcommands()} */
    public boolean handle(CommandSender sender, String[] args) {
        if (args.length == 0) {
            return false;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "stats" -> showStats(sender, args);
            case "top" -> showTop(sender, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Completions for the argument being typed after {@code stats} or {@code top}. */
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length != 2) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        if (args[0].equalsIgnoreCase("stats")) {
            options.addAll(stats.knownNames());
        } else if (args[0].equalsIgnoreCase("top")) {
            for (StatsManager.Ranking ranking : StatsManager.Ranking.values()) {
                options.add(ranking.name().toLowerCase(Locale.ROOT));
            }
        }
        String typed = args[1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }

    private void showStats(CommandSender sender, String[] args) {
        UUID target;
        String name;
        if (args.length >= 2) {
            name = args[1];
            target = stats.findByName(name);
        } else if (sender instanceof Player player) {
            name = player.getName();
            target = player.getUniqueId();
        } else {
            send(sender, config.prefixed("stats-console"));
            return;
        }
        PlayerStats own = target == null ? null : stats.stats(target);
        if (own == null) {
            send(sender, config.prefixed("stats-none", "player", name));
            return;
        }
        Object[] values = {
            "player", own.name() == null ? name : own.name(),
            "rounds", own.rounds(),
            "wins", own.wins(),
            "win_rate", own.winRatePercent(),
            "wagered", config.money(own.wagered()),
            "won", config.money(own.paid()),
            "net", signed(own.net()),
            "biggest_win", config.money(own.biggestWin())
        };
        send(sender, config.message("stats-header", values));
        for (String line : config.messageList("stats-lines", values)) {
            send(sender, line);
        }
    }

    private void showTop(CommandSender sender, String[] args) {
        StatsManager.Ranking ranking = args.length >= 2 ? StatsManager.Ranking.parse(args[1]) : StatsManager.Ranking.NET;
        if (ranking == null) {
            send(sender, config.prefixed("top-usage"));
            return;
        }
        List<PlayerStats> top = stats.top(ranking, TOP_SIZE);
        if (top.isEmpty()) {
            send(sender, config.prefixed("top-empty"));
            return;
        }
        send(sender, config.message("top-header", "count", top.size(),
                "ranking", config.message("top-ranking-" + ranking.name().toLowerCase(Locale.ROOT))));
        int rank = 1;
        for (PlayerStats entry : top) {
            long value = ranking.of(entry);
            send(sender, config.message("top-line", "rank", rank++, "player", String.valueOf(entry.name()),
                    "value", ranking.isMoney() ? config.money(value) : Long.toString(value)));
        }
    }

    /** A net result as "+$120" in green or "-$50" in red. */
    private String signed(long net) {
        return ConfigManager.color(net < 0 ? "&c-" : "&a+") + config.money(Math.abs(net));
    }

    private static void send(CommandSender sender, String message) {
        if (!message.isEmpty()) {
            sender.sendMessage(message);
        }
    }
}
