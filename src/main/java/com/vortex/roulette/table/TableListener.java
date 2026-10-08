package com.vortex.roulette.table;

import com.vortex.roulette.RoulettePlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

/** Leaving a table by standing up, quitting or dying; keeping its blocks whole; clearing up stray entities. */
final class TableListener implements Listener {
    private final RoulettePlugin plugin;
    private final TableManager manager;

    TableListener(RoulettePlugin plugin, TableManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    /** Sneaking, a teleport, a world change or the loss of the stand all take the player off it: that is leaving. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player
                && event.getDismounted().getScoreboardTags().contains(RouletteTable.SEAT_TAG)) {
            RouletteTable table = manager.tableOf(player.getUniqueId());
            if (table != null) {
                table.leave(player, RouletteTable.Leave.STOOD_UP);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        RouletteTable table = manager.tableOf(player.getUniqueId());
        if (table != null) {
            table.leave(player, RouletteTable.Leave.GONE);
        }
        manager.chips().forget(player.getUniqueId());
    }

    // After ChipHand's LOW handler, which fixes the drops while the chip is still known.
    @EventHandler(priority = EventPriority.NORMAL)
    public void onDeath(PlayerDeathEvent event) {
        RouletteTable table = manager.tableOf(event.getEntity().getUniqueId());
        if (table != null) {
            table.leave(event.getEntity(), RouletteTable.Leave.GONE);
        }
    }

    // ------------------------------------------------------------------ the blocks

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        TableManager.TableBlock hit = manager.blockAt(event.getBlock());
        if (hit != null) {
            event.setCancelled(true);
            String message = plugin.config().prefixed("table-protected", "table", hit.table().id());
            if (!message.isEmpty()) {
                event.getPlayer().sendMessage(message);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> manager.blockAt(block) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> manager.blockAt(block) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            if (manager.blockAt(block) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            if (manager.blockAt(block) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ strays

    /**
     * Nothing a table shows is ever saved with a chunk, so an entity with our tag that comes off the disk is left
     * over from something that should not happen. Remove it rather than leave a chip nobody can pick up.
     */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity.getScoreboardTags().contains(TableAttachment.ENTITY_TAG)) {
                entity.remove();
            }
        }
    }

    /** At enable no table shows anything yet, so whatever carries the tag is left from before a /reload. */
    static void sweepLoadedWorlds() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getScoreboardTags().contains(TableAttachment.ENTITY_TAG)) {
                    entity.remove();
                }
            }
        }
    }
}
