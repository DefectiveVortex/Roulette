package com.vortex.roulette.table;

import com.vortex.roulette.RoulettePlugin;
import com.vortex.roulette.config.ConfigManager;
import com.vortex.roulette.game.TableRules;
import com.vortex.roulette.model.WheelType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The table subcommands of {@code /roulette}: create, remove, list and set for admins, menu and leave for
 * players. {@link com.vortex.roulette.command.RouletteCommand} routes to {@link #handle} and {@link #complete}.
 */
public final class TableCommands {
    private static final String ADMIN = "roulette.admin";
    private static final String PLAY = "roulette.play";
    private static final List<String> ADMIN_SUBS = List.of("create", "remove", "list", "set");
    private static final List<String> PLAYER_SUBS = List.of("menu", "leave");

    private final RoulettePlugin plugin;
    private final TableManager manager;

    public TableCommands(RoulettePlugin plugin, TableManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    /** The first words this class answers to, for the root command's own tab completion. */
    public List<String> subcommands(CommandSender sender) {
        List<String> subs = new ArrayList<>();
        if (sender.hasPermission(PLAY)) {
            subs.addAll(PLAYER_SUBS);
        }
        if (sender.hasPermission(ADMIN)) {
            subs.addAll(ADMIN_SUBS);
        }
        return subs;
    }

    /**
     * @param args everything after {@code /roulette}
     * @return false if the first word is not one of this class's subcommands; nothing was sent then
     */
    public boolean handle(CommandSender sender, String[] args) {
        if (args.length == 0) {
            return false;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        boolean admin = ADMIN_SUBS.contains(sub);
        if (!admin && !PLAYER_SUBS.contains(sub)) {
            return false;
        }
        ConfigManager config = plugin.config();
        if (!sender.hasPermission(admin ? ADMIN : PLAY)) {
            sender.sendMessage(config.prefixed("no-permission"));
            return true;
        }
        switch (sub) {
            case "create" -> create(sender, args);
            case "remove" -> {
                if (args.length < 2) {
                    sender.sendMessage(config.prefixed("table-usage-remove"));
                } else if (manager.remove(args[1])) {
                    sender.sendMessage(config.prefixed("table-removed", "table", args[1].toLowerCase(Locale.ROOT)));
                } else {
                    sender.sendMessage(config.prefixed("table-unknown", "table", args[1]));
                }
            }
            case "list" -> list(sender);
            case "set" -> set(sender, args);
            case "menu" -> {
                RouletteTable table = sender instanceof Player player ? manager.tableOf(player.getUniqueId()) : null;
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(config.prefixed("player-only-command"));
                } else if (table == null) {
                    sender.sendMessage(config.prefixed("table-not-seated"));
                } else {
                    manager.menu().open(player, table);
                }
            }
            default -> {
                RouletteTable table = sender instanceof Player player ? manager.tableOf(player.getUniqueId()) : null;
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(config.prefixed("player-only-command"));
                } else if (table == null) {
                    sender.sendMessage(config.prefixed("table-not-seated"));
                } else {
                    table.leave(player, RouletteTable.Leave.GONE);
                }
            }
        }
        return true;
    }

    private void create(CommandSender sender, String[] args) {
        ConfigManager config = plugin.config();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(config.prefixed("player-only-command"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(config.prefixed("table-usage-create"));
            return;
        }
        WheelType wheel = null;
        if (args.length >= 3) {
            try {
                wheel = WheelType.valueOf(args[2].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                sender.sendMessage(config.prefixed("table-usage-create"));
                return;
            }
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        String key = switch (manager.create(player, id, wheel)) {
            case OK -> "table-created";
            case BAD_NAME -> "table-bad-name";
            case EXISTS -> "table-exists";
            case NO_ROOM -> "table-no-room";
            case SAVE_FAILED -> "table-save-failed";
        };
        sender.sendMessage(config.prefixed(key, "table", id));
    }

    private void list(CommandSender sender) {
        ConfigManager config = plugin.config();
        if (manager.tables().isEmpty()) {
            sender.sendMessage(config.prefixed("table-list-empty"));
            return;
        }
        sender.sendMessage(config.prefixed("table-list-header", "count", manager.tables().size()));
        for (RouletteTable table : manager.tables()) {
            TableDef def = table.def();
            TableRules rules = def.rules();
            sender.sendMessage(config.message("table-list-line",
                    "table", def.id(),
                    "wheel", rules.wheel().name().toLowerCase(Locale.ROOT),
                    "world", def.world(), "x", def.x(), "y", def.y(), "z", def.z(),
                    "min", config.money(rules.minBet()), "max", config.money(rules.maxBet()),
                    "players", table.seatedCount(), "seats", TableShape.seats().size()));
        }
    }

    private void set(CommandSender sender, String[] args) {
        ConfigManager config = plugin.config();
        if (args.length < 4) {
            sender.sendMessage(config.prefixed("table-usage-set", "settings", String.join(", ", TableManager.SETTINGS)));
            return;
        }
        String key = switch (manager.set(args[1], args[2], args[3])) {
            case OK -> "table-set-done";
            case UNKNOWN_TABLE -> "table-unknown";
            case UNKNOWN_SETTING -> "table-usage-set";
            case BAD_VALUE -> "table-set-bad-value";
            case BUSY -> "table-busy";
            case SAVE_FAILED -> "table-save-failed";
        };
        sender.sendMessage(config.prefixed(key, "table", args[1].toLowerCase(Locale.ROOT),
                "setting", args[2].toLowerCase(Locale.ROOT), "value", args[3],
                "settings", String.join(", ", TableManager.SETTINGS)));
    }

    /** Tab completion for {@code args} (everything after {@code /roulette}); empty if nothing fits. */
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length < 2 || !sender.hasPermission(ADMIN)) {
            return List.of();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        if (args.length == 2 && (sub.equals("remove") || sub.equals("set"))) {
            manager.tables().forEach(t -> options.add(t.id()));
        } else if (args.length == 3 && sub.equals("create")) {
            options.addAll(List.of("european", "american"));
        } else if (args.length == 3 && sub.equals("set")) {
            options.addAll(TableManager.SETTINGS);
        } else if (args.length == 4 && sub.equals("set") && args[2].equalsIgnoreCase("wheel")) {
            options.addAll(List.of("european", "american"));
        }
        options.removeIf(option -> !option.startsWith(typed));
        return options;
    }
}
