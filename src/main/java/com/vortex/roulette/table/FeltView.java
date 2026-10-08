package com.vortex.roulette.table;

import com.vortex.roulette.RoulettePlugin;
import com.vortex.roulette.game.Round;
import com.vortex.roulette.game.RoundResult;
import com.vortex.roulette.input.ChipHand;
import com.vortex.roulette.model.BetSpot;
import com.vortex.roulette.model.BetSpots;
import com.vortex.roulette.model.Pocket;
import com.vortex.roulette.model.layout.FeltFrame.Position;
import com.vortex.roulette.model.layout.FeltLayout;
import com.vortex.roulette.model.layout.FeltLayout.Placed;
import com.vortex.roulette.model.layout.FeltPoint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * What lies on the felt: the felt texture itself, every player's chips, the marker on the winning number and each
 * seated player's own aim highlight. All of it is flat models from the pack on item displays (docs/ART-CONTRACT.md:
 * a 1x1 block quad at scale 1, texture +x along the layout at {@code displayYaw}).
 */
final class FeltView implements TableAttachment {
    /** Heights above the table top. Flat quads this close z-fight at a distance, so each layer has its own. */
    private static final double FELT_LIFT = 1 / 128.0;
    private static final double HIGHLIGHT_LIFT = 2 / 128.0;
    private static final double CHIP_LIFT = 3 / 128.0;
    private static final double CHIP_STEP = 1 / 256.0;
    private static final double MARKER_LIFT = 12 / 128.0;

    /** Sizes in felt cells, the same as resourcepack/contract.json small.shown_cells (FeltContractTest). */
    static final double CHIP_SIZE = 0.45;
    static final double MARKER_SIZE = 0.5;
    static final double LINE_HIGHLIGHT = 0.6;
    /** On a cell bet the highlight is stretched to this share of the cell instead. */
    private static final double CELL_HIGHLIGHT = 0.92;
    /** Chips of several players on one spot stand on a ring this far from it. */
    private static final double SHARE_RADIUS = 0.2;

    private final RoulettePlugin plugin;
    private final RouletteTable table;
    private final ChipHand chips;
    private final FeltLayout layout;

    private ItemDisplay felt;
    private ItemDisplay marker;
    private final Map<BetSpot, Map<UUID, ItemDisplay>> piles = new LinkedHashMap<>();
    private final Map<UUID, ItemDisplay> highlights = new HashMap<>();
    private final Map<UUID, BetSpot> aimed = new HashMap<>();
    private boolean shown;

    FeltView(RoulettePlugin plugin, RouletteTable table, ChipHand chips) {
        this.plugin = plugin;
        this.table = table;
        this.chips = chips;
        this.layout = FeltLayout.of(table.wheel());
    }

    @Override
    public void show() {
        if (shown || table.world() == null) {
            return;
        }
        shown = true;
        Position centre = table.placement().feltCentre(FELT_LIFT);
        double cell = TableShape.BLOCKS_PER_CELL;
        felt = spawn(centre, new NamespacedKey("roulette", "felt_" + table.wheel().name().toLowerCase(java.util.Locale.ROOT)),
                FeltLayout.CANVAS_WIDTH * cell, FeltLayout.CANVAS_HEIGHT * cell, true);
        Round round = table.roundIfAny();
        if (round != null) {
            for (UUID player : round.bettors()) {
                for (Map.Entry<BetSpot, Long> bet : round.betsOf(player).entrySet()) {
                    setChip(player, bet.getKey(), bet.getValue());
                }
            }
        }
    }

    @Override
    public void hide() {
        shown = false;
        remove(felt);
        felt = null;
        clearChips();
        highlights.values().forEach(FeltView::remove);
        highlights.clear();
        aimed.clear();
    }

    // ------------------------------------------------------------------ the round

    @Override
    public void betPlaced(Round round, UUID player, BetSpot spot, long amount, long playerOnSpot) {
        setChip(player, spot, playerOnSpot);
    }

    @Override
    public void betRemoved(Round round, UUID player, BetSpot spot, long amount, long playerOnSpot) {
        setChip(player, spot, playerOnSpot);
    }

    @Override
    public void resultSettled(Round round, RoundResult result) {
        if (!shown) {
            return;
        }
        Pocket pocket = result.pocket();
        for (BetSpot spot : new ArrayList<>(piles.keySet())) {
            if (!spot.covers(pocket)) {
                piles.remove(spot).values().forEach(FeltView::remove);
            }
        }
        remove(marker);
        FeltPoint anchor = layout.anchor(BetSpots.of(table.wheel()).straight(pocket));
        double size = MARKER_SIZE * TableShape.BLOCKS_PER_CELL;
        marker = spawn(table.placement().felt(MARKER_LIFT).toWorld(anchor), new NamespacedKey("roulette", "marker"),
                size, size, true);
    }

    @Override
    public void cleared(Round round) {
        clearChips();
    }

    private void clearChips() {
        for (Map<UUID, ItemDisplay> pile : piles.values()) {
            pile.values().forEach(FeltView::remove);
        }
        piles.clear();
        remove(marker);
        marker = null;
    }

    private void setChip(UUID player, BetSpot spot, long amount) {
        if (!shown) {
            return;
        }
        Map<UUID, ItemDisplay> pile = piles.computeIfAbsent(spot, s -> new LinkedHashMap<>());
        ItemDisplay chip = pile.get(player);
        if (amount <= 0) {
            remove(pile.remove(player));
        } else {
            ItemStack item = flatItem(ChipHand.model(chips.tierFor(amount)));
            if (chip == null || !chip.isValid()) {
                double size = CHIP_SIZE * TableShape.BLOCKS_PER_CELL;
                chip = spawn(table.placement().felt(CHIP_LIFT).toWorld(layout.anchor(spot)), null, size, size, true);
                pile.put(player, chip);
            }
            chip.setItemStack(item);
        }
        if (pile.isEmpty()) {
            piles.remove(spot);
        } else {
            arrange(spot, pile);
        }
    }

    /** One player's chip sits on the spot itself; several stand around it so each can be seen. */
    private void arrange(BetSpot spot, Map<UUID, ItemDisplay> pile) {
        FeltPoint anchor = layout.anchor(spot);
        int n = pile.size();
        int i = 0;
        for (ItemDisplay chip : pile.values()) {
            double u = anchor.u();
            double v = anchor.v();
            if (n > 1) {
                double angle = 2 * Math.PI * i / n;
                u += SHARE_RADIUS * Math.cos(angle);
                v += SHARE_RADIUS * Math.sin(angle);
            }
            Position p = table.placement().felt(CHIP_LIFT + i * CHIP_STEP).toWorld(u, v);
            Location to = chip.getLocation();
            to.set(p.x(), p.y(), p.z());
            if (to.distanceSquared(chip.getLocation()) > 1e-8) {
                chip.teleport(to);
            }
            i++;
        }
    }

    // ------------------------------------------------------------------ the aim highlight

    /** Shows this player (and only this player) where their chip would land; null takes the highlight away. */
    void aim(Player player, Placed target) {
        if (!shown) {
            return;
        }
        UUID id = player.getUniqueId();
        BetSpot spot = target == null ? null : target.spot();
        if (java.util.Objects.equals(aimed.get(id), spot) && (spot == null || valid(highlights.get(id)))) {
            return;
        }
        ItemDisplay ring = highlights.get(id);
        if (spot == null) {
            aimed.remove(id);
            if (ring != null) {
                ring.setViewRange(0);
            }
            return;
        }
        aimed.put(id, spot);
        double cell = TableShape.BLOCKS_PER_CELL;
        double width = target.onLine() ? LINE_HIGHLIGHT : target.cell().width() * CELL_HIGHLIGHT;
        double depth = target.onLine() ? LINE_HIGHLIGHT : target.cell().height() * CELL_HIGHLIGHT;
        Position p = table.placement().felt(HIGHLIGHT_LIFT).toWorld(target.anchor());
        if (!valid(ring)) {
            ring = spawn(p, new NamespacedKey("roulette", "highlight"), width * cell, depth * cell, false);
            ring.setTeleportDuration(1);
            player.showEntity(plugin, ring);
            highlights.put(id, ring);
            return;
        }
        ring.setTransformation(scale(width * cell, depth * cell));
        Location to = ring.getLocation();
        to.set(p.x(), p.y(), p.z());
        ring.teleport(to);
        ring.setViewRange(1);
    }

    void forget(UUID player) {
        aimed.remove(player);
        remove(highlights.remove(player));
    }

    // ------------------------------------------------------------------ entities

    private ItemDisplay spawn(Position at, NamespacedKey model, double width, double depth, boolean forEveryone) {
        World world = table.world();
        Location where = new Location(world, at.x(), at.y(), at.z(), table.placement().displayYaw(), 0f);
        return world.spawn(where, ItemDisplay.class, display -> {
            display.setPersistent(false);
            display.addScoreboardTag(ENTITY_TAG);
            display.setVisibleByDefault(forEveryone);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setTransformation(scale(width, depth));
            if (model != null) {
                display.setItemStack(flatItem(model));
            }
        });
    }

    private static Transformation scale(double width, double depth) {
        return new Transformation(new Vector3f(), new Quaternionf(),
                new Vector3f((float) width, 1f, (float) depth), new Quaternionf());
    }

    private static ItemStack flatItem(NamespacedKey model) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(model);
        item.setItemMeta(meta);
        return item;
    }

    private static boolean valid(ItemDisplay display) {
        return display != null && display.isValid();
    }

    private static void remove(ItemDisplay display) {
        if (display != null) {
            display.remove();
        }
    }
}
