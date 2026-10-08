package com.vortex.roulette.pack;

import com.vortex.roulette.RoulettePlugin;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/**
 * Offers the Roulette textures to players and remembers who has them.
 *
 * <p>The plugin does not host the pack: {@code resource-pack.url} in config.yml points at the zip. It is added on
 * top of the server's own pack, never in place of it. With no url nothing is sent, and players who installed the
 * pack by hand simply see the table; the plugin cannot know about them, so {@link #hasPack} is false for them.
 */
public final class PackDelivery implements Listener {

    /** A fixed id, so a second offer replaces our pack on the client instead of stacking a copy. */
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("roulette:textures".getBytes(StandardCharsets.UTF_8));

    private final RoulettePlugin plugin;
    private final Set<UUID> offered = ConcurrentHashMap.newKeySet();
    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();

    public PackDelivery(RoulettePlugin plugin) {
        this.plugin = plugin;
    }

    /** Call when a player sits down at a table. Offers the pack once per session if config says "table". */
    public void offerAtTable(Player player) {
        if (sendWhen().equals("table")) {
            offer(player);
        }
    }

    /** True once the player's client has reported that it loaded the pack this plugin sent. */
    public boolean hasPack(Player player) {
        return loaded.contains(player.getUniqueId());
    }

    private String sendWhen() {
        String when = plugin.config().raw().getString("resource-pack.send", "table");
        return when == null ? "table" : when.trim().toLowerCase(Locale.ROOT);
    }

    private void offer(Player player) {
        FileConfiguration config = plugin.config().raw();
        String url = config.getString("resource-pack.url", "");
        if (url == null || url.isBlank() || !offered.add(player.getUniqueId())) {
            return;
        }
        String prompt = plugin.config().message("resource-pack-prompt");
        player.addResourcePack(PACK_ID, url.trim(), sha1(config.getString("resource-pack.sha1", "")),
                prompt.isEmpty() ? null : prompt, config.getBoolean("resource-pack.required", false));
    }

    /** The 20 bytes of the configured hash, or null (send without one) if it is empty or not a SHA-1. */
    private byte[] sha1(String hex) {
        if (hex == null || hex.isBlank()) {
            return null;
        }
        try {
            byte[] hash = HexFormat.of().parseHex(hex.trim());
            if (hash.length == 20) {
                return hash;
            }
        } catch (IllegalArgumentException ignored) {
            // falls through to the warning
        }
        plugin.getLogger().warning("resource-pack.sha1 is not a SHA-1 hash; sending the pack without one.");
        return null;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (sendWhen().equals("join")) {
            offer(event.getPlayer());
        }
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (!PACK_ID.equals(event.getID())) {
            return;
        }
        UUID player = event.getPlayer().getUniqueId();
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> loaded.add(player);
            case ACCEPTED, DOWNLOADED -> { }
            default -> loaded.remove(player);   // declined, failed or discarded: fall back to the chest menu icons
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        offered.remove(event.getPlayer().getUniqueId());
        loaded.remove(event.getPlayer().getUniqueId());
    }
}
