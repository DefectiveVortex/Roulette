package com.vortex.roulette.input;

import com.vortex.roulette.RoulettePlugin;
import com.vortex.roulette.config.ConfigManager;
import com.vortex.roulette.game.PlaceResult;
import com.vortex.roulette.game.Round;
import com.vortex.roulette.model.BetSpot;
import com.vortex.roulette.model.layout.FeltLayout.Placed;
import com.vortex.roulette.table.RouletteTable;
import com.vortex.roulette.table.SpotNames;
import com.vortex.roulette.table.TableManager;
import com.vortex.roulette.table.TableManager.TableBlock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Betting by aiming. While a player is seated every click belongs to the table: the look ray is laid over the felt
 * and snapped to a bet spot, right-click puts a chip of the selected value there and left-click takes one back.
 * The mouse wheel changes the chip, F opens the menu. The same clicks never reach the world: no block is placed,
 * broken or used and nobody is hit.
 */
public final class TableInput implements Listener {
    /** A refusal is repeated in chat at most this often, however fast the player clicks. */
    private static final int REFUSAL_TICKS = 30;
    /** The action bar fades after about two seconds; resend what the player aims at before it does. */
    private static final int ACTION_BAR_TICKS = 30;

    private static final class State {
        int lastRightClick = -10;
        int ignoreSwingUntil = -10;
        /** The click that sat the player down must not also bet; its second packet and its arm swing follow at once. */
        int ignoreClicksUntil = -10;
        int lastRefusal = -1000;
        PlaceResult lastRefused;
        String actionBar = "";
        int actionBarSent = -1000;
    }

    private final RoulettePlugin plugin;
    private final TableManager manager;
    private final Map<UUID, State> states = new HashMap<>();

    public TableInput(RoulettePlugin plugin, TableManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    private State state(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), id -> new State());
    }

    // ------------------------------------------------------------------ where the player looks

    /** Moves every seated player's highlight to the spot they look at and names it in their action bar. */
    public void trackAims(int tick) {
        ConfigManager config = plugin.config();
        for (RouletteTable table : manager.tables()) {
            if (table.seatedCount() == 0) {
                continue;
            }
            for (Player player : table.players()) {
                Placed target = manager.menu().isOpen(player) ? null : table.aimOf(player).orElse(null);
                table.showAim(player, target);
                String text = "";
                if (target != null) {
                    BetSpot spot = target.spot();
                    Round round = table.round();
                    long mine = round == null ? 0 : round.betsOf(player.getUniqueId()).getOrDefault(spot, 0L);
                    text = config.message(mine > 0 ? "aim-spot-staked" : "aim-spot",
                            "spot", SpotNames.name(config, spot),
                            "payout", spot.type().payout(),
                            "stake", config.money(mine),
                            "chip", config.money(manager.chips().selectedValue(player)));
                }
                State state = state(player);
                if (!text.equals(state.actionBar) || (!text.isEmpty() && tick - state.actionBarSent >= ACTION_BAR_TICKS)) {
                    state.actionBar = text;
                    state.actionBarSent = tick;
                    player.sendActionBar(ChipHand.text(text));
                }
            }
        }
    }

    // ------------------------------------------------------------------ clicks

    // LOW so the click is claimed before sit plugins (GSit sits players on stairs) see it. Not ignoreCancelled:
    // a click in the air arrives already cancelled.
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Action action = event.getAction();
        if (action == Action.PHYSICAL) {
            return;
        }
        RouletteTable table = manager.tableOf(player.getUniqueId());
        if (table != null) {
            event.setCancelled(true);
            if (event.getHand() == EquipmentSlot.HAND
                    && (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)) {
                rightClick(player, table);
            }
            return; // left clicks are taken from the arm swing, which also arrives when no block is in reach
        }
        if (action != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null || player.isSneaking()) {
            return; // sneak-click still places blocks against a table, for building around it
        }
        TableBlock hit = manager.blockAt(event.getClickedBlock());
        if (hit == null) {
            return;
        }
        event.setCancelled(true);
        ConfigManager config = plugin.config();
        if (!player.hasPermission("roulette.play")) {
            player.sendMessage(config.prefixed("no-permission"));
            return;
        }
        int seat = hit.seat() >= 0 ? hit.seat() : hit.table().nearestFreeSeat(player.getLocation());
        if (hit.seat() >= 0 && hit.table().freeSeat(seat) != seat) {
            player.sendMessage(config.prefixed("table-seat-taken"));
            return;
        }
        switch (hit.table().sit(player, seat)) {
            case OK -> state(player).ignoreClicksUntil = Bukkit.getCurrentTick() + 6;
            case FULL -> player.sendMessage(config.prefixed("table-full"));
            case SEAT_TAKEN -> player.sendMessage(config.prefixed("table-seat-taken"));
            case NO_ECONOMY -> player.sendMessage(config.prefixed("economy-unavailable"));
            default -> { }
        }
    }

    private void rightClick(Player player, RouletteTable table) {
        State state = state(player);
        int now = Bukkit.getCurrentTick();
        if (state.lastRightClick == now || now <= state.ignoreClicksUntil) {
            return; // one click can arrive as a block click and an item use
        }
        state.lastRightClick = now;
        if (!manager.chips().isHolding(player) || manager.chips().tierHeld(player) < 0) {
            manager.menu().open(player, table);
            return;
        }
        Placed target = table.aimOf(player).orElse(null);
        Round round = table.round();
        if (target == null || round == null) {
            return;
        }
        PlaceResult result = round.place(player.getUniqueId(), target.spot(), manager.chips().selectedValue(player));
        report(player, table, state, result, now);
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Player player = event.getPlayer();
        RouletteTable table = manager.tableOf(player.getUniqueId());
        if (table == null) {
            return;
        }
        State state = state(player);
        int now = Bukkit.getCurrentTick();
        if (now <= state.ignoreSwingUntil || now <= state.ignoreClicksUntil || now - state.lastRightClick <= 1 || manager.menu().isOpen(player)) {
            return; // the arm also swings after a right-click and after pressing the drop key
        }
        Placed target = table.aimOf(player).orElse(null);
        Round round = table.round();
        if (target == null || round == null) {
            return;
        }
        PlaceResult result = round.take(player.getUniqueId(), target.spot(), manager.chips().selectedValue(player));
        report(player, table, state, result, now);
    }

    private void report(Player player, RouletteTable table, State state, PlaceResult result, int now) {
        if (result == PlaceResult.OK) {
            state.actionBar = ""; // the stake on the spot changed: redraw the action bar next pass
            return;
        }
        if (result == state.lastRefused && now - state.lastRefusal < REFUSAL_TICKS) {
            return;
        }
        state.lastRefused = result;
        state.lastRefusal = now;
        String message = plugin.config().refusal(result, table.rules());
        if (!message.isEmpty()) {
            player.sendMessage(message);
        }
    }

    /** The mouse wheel changes the chip, not the slot. Wheel up is a bigger chip. */
    @EventHandler
    public void onScroll(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (manager.tableOf(player.getUniqueId()) == null || !manager.chips().isHolding(player)) {
            return;
        }
        event.setCancelled(true);
        int delta = event.getNewSlot() - event.getPreviousSlot();
        if (delta == 8) {
            delta = -1;
        } else if (delta == -8) {
            delta = 1;
        }
        if (delta != 1 && delta != -1) {
            return; // a number key: stay on the chip
        }
        int tier = manager.chips().scroll(player, -delta);
        State state = state(player);
        state.actionBar = plugin.config().message("chip-selected", "amount",
                plugin.config().money(manager.chips().value(tier)));
        state.actionBarSent = Bukkit.getCurrentTick();
        player.sendActionBar(ChipHand.text(state.actionBar));
    }

    /** F opens the menu instead of swapping hands. */
    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        RouletteTable table = manager.tableOf(player.getUniqueId());
        if (table != null) {
            event.setCancelled(true);
            manager.menu().open(player, table);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (manager.chips().isChip(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            state(event.getPlayer()).ignoreSwingUntil = Bukkit.getCurrentTick() + 2;
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && manager.tableOf(player.getUniqueId()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (manager.tableOf(event.getPlayer().getUniqueId()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (manager.tableOf(event.getPlayer().getUniqueId()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }
}
