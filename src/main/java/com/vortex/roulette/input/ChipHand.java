package com.vortex.roulette.input;

import com.vortex.roulette.RoulettePlugin;
import com.vortex.roulette.config.ConfigManager;
import com.vortex.roulette.util.SafeYaml;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * The chip a seated player holds. It is one real item in one hotbar slot; scrolling changes its value instead of
 * the slot. It must never cost a player an item, so (as in Legally Not Uno's card fan):
 *
 * <ul>
 * <li>the chip takes an empty hotbar slot if there is one; otherwise the stack in hand is moved into free
 *     inventory space, where it is still the player's;</li>
 * <li>only when the whole inventory is full is that stack taken into custody, and then it is written to
 *     held-items.yml and the player file is saved <em>before</em> anything else happens, so a crash or power cut
 *     at any moment either leaves the stack in the inventory or hands it back at the next join, never both and
 *     never neither;</li>
 * <li>the chip is only ever written into a slot that is empty or already holds a chip, and nothing may move a
 *     chip: not a click, a drag, a number key, the offhand key, a drop or a death.</li>
 * </ul>
 */
public final class ChipHand implements Listener {
    public static final int TIERS = 6;
    private static final long[] DEFAULT_VALUES = {10, 50, 100, 250, 500, 1000};
    private static final Material[] BASE = {
        Material.WHITE_DYE, Material.RED_DYE, Material.BLUE_DYE,
        Material.GREEN_DYE, Material.BLACK_DYE, Material.PURPLE_DYE
    };
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private static final class Hand {
        int slot = -1;
        int tier;
        ItemStack displaced;
    }

    private final RoulettePlugin plugin;
    private final NamespacedKey chipKey;
    private final File custodyFile;
    private final Map<UUID, Hand> hands = new HashMap<>();
    /** The chip a player last chose, kept while they are online so sitting down again starts from it. */
    private final Map<UUID, Integer> lastTier = new HashMap<>();
    private long[] values = DEFAULT_VALUES.clone();

    public ChipHand(RoulettePlugin plugin) {
        this.plugin = plugin;
        this.chipKey = new NamespacedKey(plugin, "chip");
        this.custodyFile = new File(plugin.getDataFolder(), "held-items.yml");
        reload();
    }

    /** Re-reads {@code chips.values} from config.yml. */
    public void reload() {
        List<Long> configured = plugin.config().raw().getLongList("chips.values");
        long[] read = new long[TIERS];
        boolean ok = configured.size() == TIERS;
        for (int i = 0; ok && i < TIERS; i++) {
            read[i] = configured.get(i);
            ok = read[i] > 0 && (i == 0 || read[i] > read[i - 1]);
        }
        if (!ok) {
            plugin.getLogger().warning("config.yml chips.values must be " + TIERS
                    + " rising amounts above 0; using 10, 50, 100, 250, 500, 1000.");
            read = DEFAULT_VALUES.clone();
        }
        values = read;
        for (Map.Entry<UUID, Hand> entry : hands.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null) {
                write(player, entry.getValue());
            }
        }
    }

    public long value(int tier) {
        return values[Math.max(0, Math.min(TIERS - 1, tier))];
    }

    /** The highest chip that is not worth more than {@code amount}; the lowest chip if none is. */
    public int tierFor(long amount) {
        int tier = 0;
        for (int i = 1; i < TIERS; i++) {
            if (values[i] <= amount) {
                tier = i;
            }
        }
        return tier;
    }

    public static NamespacedKey model(int tier) {
        return new NamespacedKey("roulette", "chip_" + (tier + 1));
    }

    public boolean isHolding(Player player) {
        return hands.containsKey(player.getUniqueId());
    }

    /** The chip tier (0..5) the player has selected; their last choice if they hold no chip right now. */
    public int tier(Player player) {
        Hand hand = hands.get(player.getUniqueId());
        return hand != null ? hand.tier : lastTier.getOrDefault(player.getUniqueId(), 0);
    }

    /** The slot the chip is in, or -1 if the player holds none (a full inventory that could not be recorded). */
    public int tierHeld(Player player) {
        Hand hand = hands.get(player.getUniqueId());
        return hand == null || hand.slot < 0 ? -1 : hand.tier;
    }

    public long selectedValue(Player player) {
        return value(tier(player));
    }

    /** Puts the chip in the player's hand. */
    public void give(Player player) {
        Hand hand = hands.computeIfAbsent(player.getUniqueId(), id -> new Hand());
        hand.tier = lastTier.getOrDefault(player.getUniqueId(), 0);
        clearStrays(player, -1);
        claimSlot(player, hand);
        write(player, hand);
    }

    /** Takes the chip away and gives back whatever it had pushed aside. */
    public void take(Player player) {
        Hand hand = hands.remove(player.getUniqueId());
        clearStrays(player, -1);
        if (hand != null) {
            restoreDisplaced(player, hand);
        }
    }

    /** Scrolls the selection one chip up or down, stopping at the ends. Returns the new tier. */
    public int scroll(Player player, int delta) {
        Hand hand = hands.get(player.getUniqueId());
        if (hand == null) {
            return tier(player);
        }
        select(player, hand.tier + delta);
        return hand.tier;
    }

    public void select(Player player, int tier) {
        int clamped = Math.max(0, Math.min(TIERS - 1, tier));
        lastTier.put(player.getUniqueId(), clamped);
        Hand hand = hands.get(player.getUniqueId());
        if (hand != null && hand.tier != clamped) {
            hand.tier = clamped;
            write(player, hand);
        }
    }

    /** Makes sure the chip is still where it belongs (after a respawn, another plugin, a desync). */
    public void resync(Player player) {
        Hand hand = hands.get(player.getUniqueId());
        if (hand != null) {
            write(player, hand);
        }
    }

    public void forget(UUID player) {
        lastTier.remove(player);
    }

    /** Plugin disable: every chip goes, every displaced stack comes back. */
    public void shutdown() {
        for (UUID id : new ArrayList<>(hands.keySet())) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                take(player);
            }
        }
        hands.clear();
    }

    public ItemStack chipItem(int tier) {
        ConfigManager config = plugin.config();
        ItemStack item = new ItemStack(BASE[tier]);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(model(tier));
        meta.displayName(text(config.message("chip-name", "amount", config.money(value(tier)))));
        List<Component> lore = new ArrayList<>();
        for (String line : config.messageList("chip-lore")) {
            lore.add(text(line));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(chipKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isChip(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        return stack.getItemMeta().getPersistentDataContainer().has(chipKey, PersistentDataType.BYTE);
    }

    static Component text(String legacy) {
        return LEGACY.deserialize(legacy).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    // ------------------------------------------------------------------ the slot

    private void claimSlot(Player player, Hand hand) {
        PlayerInventory inv = player.getInventory();
        restoreDisplaced(player, hand); // never stack two displacements
        int current = inv.getHeldItemSlot();
        if (isFree(inv.getItem(current))) {
            hand.slot = current;
            return;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (isFree(inv.getItem(slot))) {
                hand.slot = slot;
                inv.setHeldItemSlot(slot);
                return;
            }
        }
        // Hotbar full: move what is in hand into the backpack, where it is still the player's.
        ItemStack occupant = inv.getItem(current);
        hand.slot = current;
        int free = inv.firstEmpty();
        if (free >= 0) {
            inv.setItem(free, occupant);
            inv.setItem(current, null);
            send(player, "chip-slot-freed", occupant);
            return;
        }
        // Nowhere to put it. Record it on disk first, then take it, then make the player file agree.
        ItemStack kept = occupant.clone();
        if (!writeCustody(player.getUniqueId(), current, kept)) {
            // Could not record it: do not take it. The chip then has no slot and the player cannot bet by aiming.
            hand.slot = -1;
            send(player, "chip-no-room", occupant);
            return;
        }
        hand.displaced = kept;
        inv.setItem(current, null);
        player.saveData();
        send(player, "chip-slot-held", occupant);
    }

    /** The chip may only be written into a slot that is empty or already holds a chip. */
    private void write(Player player, Hand hand) {
        PlayerInventory inv = player.getInventory();
        if (hand.slot < 0 || hand.slot > 8 || !isFree(inv.getItem(hand.slot))) {
            claimSlot(player, hand);
            if (hand.slot < 0) {
                return;
            }
        }
        clearStrays(player, hand.slot);
        ItemStack chip = chipItem(hand.tier);
        if (!chip.equals(inv.getItem(hand.slot))) {
            inv.setItem(hand.slot, chip);
        }
        if (inv.getHeldItemSlot() != hand.slot) {
            inv.setHeldItemSlot(hand.slot);
        }
    }

    private void restoreDisplaced(Player player, Hand hand) {
        ItemStack back = hand.displaced;
        if (back == null) {
            return;
        }
        hand.displaced = null;
        PlayerInventory inv = player.getInventory();
        if (hand.slot >= 0 && hand.slot < 9 && isFree(inv.getItem(hand.slot))) {
            inv.setItem(hand.slot, back);
        } else {
            for (ItemStack overflow : inv.addItem(back).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), overflow);
                send(player, "chip-item-dropped", overflow);
            }
        }
        // The player file must say "has it back" before the record goes, or a crash in between loses it.
        player.saveData();
        writeCustody(player.getUniqueId(), -1, null);
    }

    private boolean isFree(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0 || isChip(stack);
    }

    private void clearStrays(Player player, int keepSlot) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (i != keepSlot && isChip(inv.getItem(i))) {
                inv.setItem(i, null);
            }
        }
        if (isChip(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }
    }

    private void send(Player player, String key, ItemStack stack) {
        String raw = stack == null ? "item" : stack.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        String message = plugin.config().prefixed(key, "item", raw);
        if (!message.isEmpty()) {
            player.sendMessage(message);
        }
    }

    // ------------------------------------------------------------------ custody on disk

    /** Records (or with a null stack forgets) the one stack held for a player. False if it could not be written. */
    private boolean writeCustody(UUID player, int slot, ItemStack stack) {
        YamlConfiguration doc = SafeYaml.load(custodyFile, plugin.getLogger(), false).config();
        String key = player.toString();
        if (stack == null) {
            if (!doc.contains(key)) {
                return true;
            }
            doc.set(key, null);
        } else {
            doc.set(key + ".slot", slot);
            doc.set(key + ".item", Base64.getEncoder().encodeToString(stack.serializeAsBytes()));
        }
        try {
            if (doc.getKeys(false).isEmpty()) {
                java.nio.file.Files.deleteIfExists(custodyFile.toPath());
            } else {
                SafeYaml.saveAtomically(doc, custodyFile);
            }
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Could not write held-items.yml: " + e.getMessage());
            return false;
        }
    }

    /**
     * After a crash: chips left in the inventory go, and a stack that was in custody comes back, unless the
     * player file was saved before it was taken and so still has it in that very slot.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        clearStrays(player, -1);
        if (!custodyFile.exists()) {
            return;
        }
        YamlConfiguration doc = SafeYaml.load(custodyFile, plugin.getLogger(), false).config();
        String key = player.getUniqueId().toString();
        String encoded = doc.getString(key + ".item");
        if (encoded == null) {
            return;
        }
        ItemStack kept;
        try {
            kept = ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
        } catch (RuntimeException e) {
            plugin.getLogger().severe("held-items.yml: the item kept for " + player.getName()
                    + " cannot be read (" + e + "). The entry is left in the file.");
            return;
        }
        int slot = doc.getInt(key + ".slot", -1);
        PlayerInventory inv = player.getInventory();
        ItemStack there = slot >= 0 && slot < inv.getSize() ? inv.getItem(slot) : null;
        if (!kept.equals(there)) {
            if (slot >= 0 && slot < inv.getSize() && isFree(there)) {
                inv.setItem(slot, kept);
            } else {
                for (ItemStack overflow : inv.addItem(kept).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), overflow);
                }
            }
            send(player, "chip-item-returned", kept);
            plugin.getLogger().info("Returned an item held for " + player.getName() + " when the server stopped.");
        }
        player.saveData();
        writeCustody(player.getUniqueId(), -1, null);
    }

    // ------------------------------------------------------------------ nothing may move a chip

    /** Dying must not drop the chip, and must drop a stack the chip had pushed aside as if it were still there. */
    @EventHandler(priority = EventPriority.LOW)
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isChip);
        Hand hand = hands.get(event.getEntity().getUniqueId());
        if (hand != null && hand.displaced != null && !event.getKeepInventory()) {
            event.getDrops().add(hand.displaced);
            hand.displaced = null;
            // the drop exists in the world from here on; forget the record once the player file agrees
            Player player = event.getEntity();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                player.saveData();
                writeCustody(player.getUniqueId(), -1, null);
            });
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (isChip(event.getCurrentItem()) || isChip(event.getCursor())) {
            event.setCancelled(true);
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0
                && isChip(player.getInventory().getItem(event.getHotbarButton()))) {
            event.setCancelled(true);
            return;
        }
        if (event.getClick() == ClickType.SWAP_OFFHAND && isChip(player.getInventory().getItemInOffHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (isChip(event.getOldCursor())) {
            event.setCancelled(true);
            return;
        }
        for (ItemStack dragged : event.getNewItems().values()) {
            if (isChip(dragged)) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
