package com.vortex.roulette.gui;

import com.vortex.roulette.RoulettePlugin;
import com.vortex.roulette.config.ConfigManager;
import com.vortex.roulette.game.PlaceResult;
import com.vortex.roulette.game.Round;
import com.vortex.roulette.game.RoundPhase;
import com.vortex.roulette.input.ChipHand;
import com.vortex.roulette.model.BetSpot;
import com.vortex.roulette.model.BetSpots;
import com.vortex.roulette.model.BetType;
import com.vortex.roulette.model.Pocket;
import com.vortex.roulette.table.RouletteTable;
import com.vortex.roulette.table.SpotNames;
import com.vortex.roulette.table.TableManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Betting without aiming, for players who do not have the resource pack or do not want to aim: one chest with
 * every number and every outside bet. Left-click puts a chip of the selected value on a bet, right-click takes one
 * back. Splits, streets, corners and lines are only on the felt.
 *
 * <pre>
 *    1  2  3  4  5  6  7  8  9
 *   10 11 12 13 14 15 16 17 18
 *   19 20 21 22 23 24 25 26 27
 *   28 29 30 31 32 33 34 35 36
 *    0 00 D1 D2 D3  . C1 C2 C3
 *   lo ev rd bk od hi chip info close
 * </pre>
 */
public final class BetMenu implements Listener {
    private static final int SIZE = 54;
    private static final int ZERO = 36;
    private static final int DOUBLE_ZERO = 37;
    private static final int DOZENS = 38;
    private static final int COLUMNS = 42;
    private static final int EVEN_MONEY = 45;
    private static final int CHIP = 51;
    private static final int INFO = 52;
    private static final int CLOSE = 53;
    private static final BetType[] EVEN_MONEY_ORDER = {
        BetType.LOW, BetType.EVEN, BetType.RED, BetType.BLACK, BetType.ODD, BetType.HIGH
    };
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private static final class View implements InventoryHolder {
        final RouletteTable table;
        final BetSpot[] spots = new BetSpot[SIZE];
        Inventory inventory;

        View(RouletteTable table) {
            this.table = table;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final RoulettePlugin plugin;
    private final TableManager manager;
    private final Map<UUID, View> open = new HashMap<>();

    public BetMenu(RoulettePlugin plugin, TableManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public boolean isOpen(Player player) {
        return open.containsKey(player.getUniqueId());
    }

    public void open(Player player, RouletteTable table) {
        View view = new View(table);
        view.inventory = Bukkit.createInventory(view, SIZE,
                text(plugin.config().message("menu-title", "table", table.id())));
        BetSpots spots = BetSpots.of(table.wheel());
        for (int n = 1; n <= 36; n++) {
            view.spots[n - 1] = spots.straight(Pocket.of(n));
        }
        view.spots[ZERO] = spots.straight(Pocket.ZERO);
        if (table.wheel().hasDoubleZero()) {
            view.spots[DOUBLE_ZERO] = spots.straight(Pocket.DOUBLE_ZERO);
        }
        for (int i = 1; i <= 3; i++) {
            view.spots[DOZENS + i - 1] = spots.dozen(i);
            view.spots[COLUMNS + i - 1] = spots.column(i);
        }
        for (int i = 0; i < EVEN_MONEY_ORDER.length; i++) {
            view.spots[EVEN_MONEY + i] = spots.evenMoney(EVEN_MONEY_ORDER[i]);
        }
        draw(player, view);
        player.openInventory(view.inventory);
        open.put(player.getUniqueId(), view);
    }

    public void close(Player player) {
        if (open.remove(player.getUniqueId()) != null) {
            player.closeInventory();
        }
    }

    /** One player's chips changed. */
    public void refresh(RouletteTable table, UUID playerId) {
        View view = open.get(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (view != null && view.table == table && player != null) {
            draw(player, view);
        }
    }

    /** The table cleared. */
    public void refreshAll(RouletteTable table) {
        for (UUID id : new ArrayList<>(open.keySet())) {
            refresh(table, id);
        }
    }

    /** A few times a second: keeps the countdown on the clock current. */
    public void tick() {
        for (Map.Entry<UUID, View> entry : open.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                entry.getValue().inventory.setItem(INFO, info(player, entry.getValue().table));
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    private void draw(Player player, View view) {
        ConfigManager config = plugin.config();
        ChipHand chips = manager.chips();
        Round round = view.table.round();
        Map<BetSpot, Long> mine = round == null ? Map.of() : round.betsOf(player.getUniqueId());
        String chip = config.money(chips.selectedValue(player));
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, 1, " ", List.of(), false);
        boolean packed = plugin.pack().hasPack(player);
        for (int slot = 0; slot < SIZE; slot++) {
            BetSpot spot = view.spots[slot];
            if (spot == null) {
                view.inventory.setItem(slot, filler);
                continue;
            }
            long stake = mine.getOrDefault(spot, 0L);
            List<String> lore = new ArrayList<>();
            lore.add(config.message("menu-lore-pays", "payout", spot.type().payout()));
            if (stake > 0) {
                lore.add(config.message("menu-lore-yours", "amount", config.money(stake)));
            }
            lore.add(config.message("menu-lore-place", "chip", chip));
            lore.add(config.message("menu-lore-take", "chip", chip));
            ItemStack item = named(material(spot), amount(spot), SpotNames.name(config, spot), lore, stake > 0);
            if (packed) {
                // the pack draws the cell itself; without it the vanilla item and its stack size stand in
                ItemMeta icon = item.getItemMeta();
                icon.setItemModel(new NamespacedKey("roulette", "menu/" + iconName(spot)));
                item.setItemMeta(icon);
                item.setAmount(1);
            }
            view.inventory.setItem(slot, item);
        }
        ItemStack chipItem = chips.chipItem(chips.tier(player));
        ItemMeta meta = chipItem.getItemMeta();
        meta.displayName(text(config.message("chip-selected", "amount", chip)));
        List<Component> lore = new ArrayList<>();
        for (String line : config.messageList("menu-chip-lore")) {
            lore.add(text(line));
        }
        meta.lore(lore);
        chipItem.setItemMeta(meta);
        view.inventory.setItem(CHIP, chipItem);
        view.inventory.setItem(INFO, info(player, view.table));
        view.inventory.setItem(CLOSE, named(Material.BARRIER, 1, config.message("menu-close"), List.of(), false));
    }

    private ItemStack info(Player player, RouletteTable table) {
        ConfigManager config = plugin.config();
        Round round = table.round();
        RoundPhase phase = table.phase();
        String title = switch (phase) {
            case BETTING -> config.message("hud-betting", "seconds", (round.ticksLeft() + 19) / 20);
            case SPINNING -> config.message("hud-spinning");
            case RESULT -> config.message("hud-result", "number",
                    round.result() == null ? "" : config.number(round.result()));
            default -> config.message("hud-idle", "min", config.money(table.rules().minBet()),
                    "max", config.money(table.rules().maxBet()));
        };
        long staked = round == null ? 0 : round.stakeOf(player.getUniqueId());
        List<String> lore = List.of(
                config.message("menu-info-staked", "amount", config.money(staked)),
                config.message("menu-info-limits", "min", config.money(table.rules().minBet()),
                        "max", config.money(table.rules().maxBet())));
        return named(Material.CLOCK, 1, title, lore, false);
    }

    private static Material material(BetSpot spot) {
        return switch (spot.type()) {
            case STRAIGHT -> switch (spot.pockets().get(0).color()) {
                case RED -> Material.RED_CONCRETE;
                case BLACK -> Material.BLACK_CONCRETE;
                default -> Material.LIME_CONCRETE;
            };
            case DOZEN -> Material.YELLOW_STAINED_GLASS;
            case COLUMN -> Material.LIGHT_BLUE_STAINED_GLASS;
            case RED -> Material.RED_WOOL;
            case BLACK -> Material.BLACK_WOOL;
            case EVEN -> Material.WHITE_WOOL;
            case ODD -> Material.LIGHT_GRAY_WOOL;
            case LOW -> Material.IRON_INGOT;
            case HIGH -> Material.GOLD_INGOT;
            default -> Material.PAPER;
        };
    }

    /** The icon's name in the pack (docs/ART-CONTRACT.md, menu icons): n_17, n_00, dozen_2, column_3, red. */
    static String iconName(BetSpot spot) {
        return switch (spot.type()) {
            case STRAIGHT -> "n_" + spot.pockets().get(0).label();
            case DOZEN -> "dozen_" + spot.index();
            case COLUMN -> "column_" + spot.index();
            default -> spot.type().name().toLowerCase(java.util.Locale.ROOT);
        };
    }

    /** The stack size shows the number, so the menu reads at a glance. */
    private static int amount(BetSpot spot) {
        if (spot.type() == BetType.STRAIGHT) {
            return Math.max(1, spot.pockets().get(0).number());
        }
        return Math.max(1, spot.index());
    }

    private static ItemStack named(Material material, int amount, String name, List<String> lore, boolean glint) {
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text(name));
        List<Component> lines = new ArrayList<>();
        for (String line : lore) {
            if (!line.isEmpty()) {
                lines.add(text(line));
            }
        }
        meta.lore(lines);
        if (glint) {
            meta.setEnchantmentGlintOverride(true);
        }
        item.setItemMeta(meta);
        return item;
    }

    private static Component text(String legacy) {
        return LEGACY.deserialize(legacy).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof View view)) {
            return;
        }
        event.setCancelled(true); // nothing in this window, and nothing shift-clicked into it, ever moves
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != view.inventory) {
            return;
        }
        if (!view.table.isSeated(player.getUniqueId())) {
            player.closeInventory();
            return;
        }
        int slot = event.getSlot();
        boolean right = event.isRightClick();
        if (!event.isLeftClick() && !right) {
            return;
        }
        if (slot == CLOSE) {
            player.closeInventory();
            return;
        }
        ChipHand chips = manager.chips();
        if (slot == CHIP) {
            chips.select(player, chips.tier(player) + (right ? -1 : 1));
            draw(player, view);
            return;
        }
        BetSpot spot = slot >= 0 && slot < SIZE ? view.spots[slot] : null;
        Round round = view.table.round();
        if (spot == null || round == null) {
            return;
        }
        long chip = chips.selectedValue(player);
        PlaceResult result = right
                ? round.take(player.getUniqueId(), spot, chip)
                : round.place(player.getUniqueId(), spot, chip);
        String refusal = plugin.config().refusal(result, view.table.rules());
        if (!refusal.isEmpty()) {
            player.sendMessage(refusal);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof View) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof View) {
            open.remove(event.getPlayer().getUniqueId());
            if (event.getPlayer() instanceof Player player) {
                // the chip may have been clicked at; make sure it is back where it belongs
                Bukkit.getScheduler().runTask(plugin, () -> manager.chips().resync(player));
            }
        }
    }
}
