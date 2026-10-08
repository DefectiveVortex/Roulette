package com.vortex.roulette.update;

import com.vortex.roulette.RoulettePlugin;
import com.vortex.roulette.config.ConfigManager;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bukkit side of the Modrinth updater: checks on enable and every {@code updates.interval-hours} off the
 * main thread, logs and tells admins about a newer version, stages it with {@link UpdateChecker} when
 * {@code updates.auto-download} is on, and answers /roulette version and /roulette update.
 */
public final class UpdateService implements Listener {

    public static final String ADMIN_PERMISSION = "roulette.admin";
    private static final long FIRST_CHECK_DELAY_TICKS = 20L * 5;
    private static final long JOIN_NOTICE_DELAY_TICKS = 40L;

    private final RoulettePlugin plugin;
    private final UpdateChecker checker;
    private final String currentVersion;
    private volatile UpdateSettings settings = UpdateSettings.DEFAULT;
    private volatile UpdateChecker.Result last = UpdateChecker.Result.NOT_CHECKED;
    private volatile String announced;
    private volatile boolean stopped;
    private BukkitTask task;

    public UpdateService(RoulettePlugin plugin) {
        this.plugin = plugin;
        this.currentVersion = plugin.getPluginMeta().getVersion();
        Path runningJar = runningJar();
        String jarName = runningJar != null ? runningJar.getFileName().toString() : plugin.getName() + ".jar";
        UpdateChecker.Server server = new UpdateChecker.Server(currentVersion, serverLoaders(),
            Bukkit.getMinecraftVersion());
        this.checker = new UpdateChecker(UpdateChecker.PROJECT_ID, server, Bukkit.getUpdateFolderFile().toPath(),
            jarName, runningJar, plugin.getLogger());
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        reload();
    }

    /** Re-read {@code updates:} and reschedule; a check follows shortly if checks are on. */
    public void reload() {
        settings = UpdateSettings.from(plugin.config().raw().getConfigurationSection("updates"));
        cancelTask();
        if (stopped || !settings.check() || !UpdateChecker.isPublished()) return;
        long period = 20L * 60 * 60 * settings.intervalHours();
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
            () -> runCheck(settings.autoDownload(), null), FIRST_CHECK_DELAY_TICKS, period);
    }

    public void stop() {
        stopped = true;
        cancelTask();
        HandlerList.unregisterAll(this);
    }

    private void cancelTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** /roulette update: check and download now, whatever the config says, and report back to {@code sender}. */
    public void checkNow(CommandSender sender) {
        if (stopped) return;
        if (!UpdateChecker.isPublished()) {
            send(sender, "version-not-published");
            return;
        }
        send(sender, "version-checking");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> runCheck(true, sender));
    }

    /** /roulette version: the running version, the latest known one and what the updater is doing about it. */
    public void sendStatus(CommandSender sender) {
        send(sender, "version-current", "version", currentVersion);
        UpdateChecker.Result r = last;
        String latest = r.latest() == null ? null : r.latest().versionNumber();
        if (latest != null) send(sender, "version-latest", "latest", latest, "channel", settings.channel().id());
        switch (r.state()) {
            case NOT_CHECKED -> send(sender, !UpdateChecker.isPublished() ? "version-not-published"
                : settings.check() ? "version-not-checked" : "version-checks-disabled");
            case UP_TO_DATE -> send(sender, "version-up-to-date");
            case AVAILABLE -> send(sender, "version-update-available", "latest", latest, "version", currentVersion);
            case STAGED -> send(sender, "version-update-downloaded", "latest", latest);
            case DOWNLOAD_FAILED -> send(sender, "version-download-failed", "latest", latest);
            case CHECK_FAILED -> send(sender, "version-check-failed");
        }
    }

    public UpdateChecker.Result getLastResult() {
        return last;
    }

    // ------------------------------------------------------------------ checking (async)

    private void runCheck(boolean download, CommandSender replyTo) {
        if (stopped) return;
        UpdateChecker.Result result = checker.check(settings, download);
        if (result.state() != UpdateChecker.State.CHECK_FAILED || last.latest() == null) last = result;
        if (stopped || !plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> afterCheck(result, replyTo));
    }

    private void afterCheck(UpdateChecker.Result r, CommandSender replyTo) {
        if (stopped) return;
        ModrinthUpdate update = ModrinthUpdate.of(r);
        if (update != null && !update.version().equals(announced)) {
            announced = update.version();
            plugin.getLogger().info("Roulette " + update.version() + " is available on Modrinth (running "
                + currentVersion + "): " + update.url());
            if (!r.newlyStaged()) Bukkit.getOnlinePlayers().forEach(this::notifyAdmin);
        }
        if (r.newlyStaged()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission(ADMIN_PERMISSION) && p != replyTo) send(p, "version-update-downloaded", "latest", update.version());
            }
        }
        if (replyTo == null || (replyTo instanceof Player p && !p.isOnline())) return;
        String latest = r.latest() == null ? "" : r.latest().versionNumber();
        switch (r.state()) {
            case UP_TO_DATE, NOT_CHECKED -> send(replyTo, "version-up-to-date");
            case AVAILABLE -> send(replyTo, "version-update-available", "latest", latest, "version", currentVersion);
            case STAGED -> send(replyTo, "version-update-downloaded", "latest", latest);
            case DOWNLOAD_FAILED -> send(replyTo, "version-download-failed", "latest", latest);
            case CHECK_FAILED -> send(replyTo, "version-check-failed");
        }
    }

    /** A newer version worth telling someone about, with its Modrinth page. */
    private record ModrinthUpdate(String version, String url) {
        static ModrinthUpdate of(UpdateChecker.Result r) {
            if (r.latest() == null) return null;
            return switch (r.state()) {
                case AVAILABLE, STAGED, DOWNLOAD_FAILED -> new ModrinthUpdate(r.latest().versionNumber(),
                    "https://modrinth.com/plugin/" + UpdateChecker.PROJECT_SLUG + "/version/" + r.latest().versionNumber());
                default -> null;
            };
        }
    }

    // ------------------------------------------------------------------ admins

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission(ADMIN_PERMISSION) || ModrinthUpdate.of(last) == null) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> notifyAdmin(player), JOIN_NOTICE_DELAY_TICKS);
    }

    private void notifyAdmin(Player player) {
        UpdateChecker.Result r = last;
        ModrinthUpdate update = ModrinthUpdate.of(r);
        if (stopped || update == null || !player.isOnline() || !player.hasPermission(ADMIN_PERMISSION)) return;
        if (r.state() == UpdateChecker.State.STAGED) {
            send(player, "version-update-downloaded", "latest", update.version());
            return;
        }
        ConfigManager cfg = plugin.config();
        if (blank(cfg.message("version-admin-notice"))) return;
        TextComponent line = new TextComponent(TextComponent.fromLegacyText(
            cfg.prefixed("version-admin-notice", "latest", update.version(), "version", currentVersion)));
        String label = cfg.message("version-open-button");
        if (!blank(label)) {
            TextComponent button = new TextComponent(TextComponent.fromLegacyText(label));
            button.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, update.url()));
            String hover = cfg.message("version-open-hover");
            if (!blank(hover)) {
                button.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(TextComponent.fromLegacyText(hover))));
            }
            line.addExtra(button);
        }
        player.spigot().sendMessage(line);
    }

    // ------------------------------------------------------------------ helpers

    private void send(CommandSender to, String key, Object... args) {
        ConfigManager cfg = plugin.config();
        if (!blank(cfg.message(key))) to.sendMessage(cfg.prefixed(key, args));
    }

    private static boolean blank(String message) {
        return message == null || ChatColor.stripColor(message).isBlank();
    }

    private Path runningJar() {
        try {
            Path path = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            return path.getFileName() != null && path.toString().endsWith(".jar") ? path : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Modrinth loader names this server can run. */
    static Set<String> serverLoaders() {
        Set<String> loaders = new HashSet<>(List.of("bukkit", "spigot"));
        if (classExists("io.papermc.paper.configuration.Configuration") || classExists("com.destroystokyo.paper.PaperConfig")) {
            loaders.add("paper");
        }
        if (classExists("org.purpurmc.purpur.PurpurConfig")) loaders.add("purpur");
        return Set.copyOf(loaders);
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, UpdateService.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
