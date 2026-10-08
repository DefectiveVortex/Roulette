package com.vortex.roulette.table;

import com.vortex.roulette.RoulettePlugin;
import com.vortex.roulette.game.RoundPhase;
import com.vortex.roulette.game.TableRules;
import com.vortex.roulette.gui.BetMenu;
import com.vortex.roulette.input.ChipHand;
import com.vortex.roulette.input.TableInput;
import com.vortex.roulette.model.WheelType;
import com.vortex.roulette.table.TableShape.Offset;
import com.vortex.roulette.table.TableShape.Placement;
import com.vortex.roulette.table.TableShape.Seat;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Every roulette table on the server: building one out of blocks, remembering it in tables.yml, finding the
 * table a block or a player belongs to, and taking it all down again.
 */
public final class TableManager {

    /** A block of a table: a stool (seat 0..6) or the table top (seat -1). */
    public record TableBlock(RouletteTable table, int seat) {
    }

    public enum CreateResult { OK, BAD_NAME, EXISTS, NO_ROOM, SAVE_FAILED }

    public enum SetResult { OK, UNKNOWN_TABLE, UNKNOWN_SETTING, BAD_VALUE, BUSY, SAVE_FAILED }

    public static final List<String> SETTINGS = List.of("wheel", "min-bet", "max-bet", "max-payout",
            "betting-seconds", "spin-seconds", "result-seconds");

    private record BlockKey(String world, int x, int y, int z) {
    }

    private final RoulettePlugin plugin;
    private final TableStore store;
    private final ChipHand chips;
    private final BetMenu menu;
    private final TableInput input;
    private final Map<String, RouletteTable> tables = new LinkedHashMap<>();
    private final Map<UUID, RouletteTable> seated = new HashMap<>();
    private final Map<BlockKey, TableBlock> blocks = new HashMap<>();
    private final List<TableAttachmentFactory> factories = new ArrayList<>();
    private BukkitTask ticker;
    private int tick;

    public TableManager(RoulettePlugin plugin) {
        this.plugin = plugin;
        this.store = new TableStore(new File(plugin.getDataFolder(), "tables.yml"), plugin.getLogger());
        this.chips = new ChipHand(plugin);
        this.menu = new BetMenu(plugin, this);
        this.input = new TableInput(plugin, this);
    }

    /** Reads tables.yml, registers the listeners and starts the aim loop. Call once from onEnable. */
    public void enable() {
        for (TableDef def : store.load(plugin.config().defaultRules())) {
            register(new RouletteTable(plugin, this, def));
        }
        Bukkit.getPluginManager().registerEvents(chips, plugin);
        Bukkit.getPluginManager().registerEvents(menu, plugin);
        Bukkit.getPluginManager().registerEvents(input, plugin);
        Bukkit.getPluginManager().registerEvents(new TableListener(plugin, this), plugin);
        TableListener.sweepLoadedWorlds();
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        plugin.getLogger().info(tables.size() + " roulette table(s) loaded.");
    }

    /** Sends everyone away, refunds every open round, gives every player their items back. Call from onDisable. */
    public void disable() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (RouletteTable table : tables.values()) {
            table.close();
        }
        chips.shutdown();
        seated.clear();
    }

    /** After /roulette reload: chip values are re-read. Tables keep their own rules. */
    public void reload() {
        chips.reload();
    }

    /** Adds something that lives on every table (the wheel). Call before or after {@link #enable}. */
    public void addAttachment(TableAttachmentFactory factory) {
        factories.add(factory);
        for (RouletteTable table : tables.values()) {
            table.attach(factory);
        }
    }

    List<TableAttachmentFactory> attachmentFactories() {
        return Collections.unmodifiableList(factories);
    }

    public ChipHand chips() {
        return chips;
    }

    public BetMenu menu() {
        return menu;
    }

    /** How far along the look ray the felt may be for an aim to count, in blocks. */
    double maxAimDistance() {
        return 12;
    }

    // ------------------------------------------------------------------ lookups

    public Collection<RouletteTable> tables() {
        return Collections.unmodifiableCollection(tables.values());
    }

    public RouletteTable table(String id) {
        return id == null ? null : tables.get(id.toLowerCase(Locale.ROOT));
    }

    /** The table this player is seated at, or null. */
    public RouletteTable tableOf(UUID player) {
        return seated.get(player);
    }

    public TableBlock blockAt(Block block) {
        return blocks.get(new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
    }

    void seated(UUID player, RouletteTable table) {
        seated.put(player, table);
    }

    void unseated(UUID player) {
        seated.remove(player);
    }

    private void tick() {
        tick++;
        if (seated.isEmpty()) {
            return;
        }
        if (tick % 2 == 0) {
            input.trackAims(tick);
        }
        if (tick % 5 == 0) {
            for (RouletteTable table : tables.values()) {
                table.tick();
            }
            menu.tick();
        }
    }

    // ------------------------------------------------------------------ building and removing

    /**
     * Builds a table in front of the player, its long side across their view and the layout the right way up for
     * them: they stand where the middle stool of the near side will be, one block back.
     */
    public CreateResult create(Player creator, String rawId, WheelType wheel) {
        String id = rawId.toLowerCase(Locale.ROOT);
        if (!TableDef.isValidId(id)) {
            return CreateResult.BAD_NAME;
        }
        if (tables.containsKey(id) || store.hasEntry(id)) {
            return CreateResult.EXISTS;
        }
        BlockFace look = cardinal(creator.getLocation().getYaw());
        // across the layout (v) points at the player; along it (u) is v turned back anticlockwise
        int vX = -look.getModX();
        int vZ = -look.getModZ();
        int uX = vZ;
        int uZ = -vX;
        Block feet = creator.getLocation().getBlock();
        int stoolX = feet.getX() + look.getModX();
        int stoolZ = feet.getZ() + look.getModZ();
        Seat middle = TableShape.seats().get(1);
        int originX = stoolX - middle.block().a() * uX - middle.block().b() * vX;
        int originZ = stoolZ - middle.block().a() * uZ - middle.block().b() * vZ;
        BlockFace facing = uX > 0 ? BlockFace.EAST : uX < 0 ? BlockFace.WEST : uZ > 0 ? BlockFace.SOUTH : BlockFace.NORTH;

        TableRules defaults = plugin.config().defaultRules();
        TableRules rules = new TableRules(wheel == null ? defaults.wheel() : wheel, defaults.minBet(), defaults.maxBet(),
                defaults.maxPayout(), defaults.bettingTicks(), defaults.spinTicks(), defaults.resultTicks());
        TableDef def = new TableDef(id, creator.getWorld().getName(), originX, feet.getY(), originZ, facing, rules);
        if (!hasRoom(creator.getWorld(), def.placement())) {
            return CreateResult.NO_ROOM;
        }
        if (!store.put(def)) {
            return CreateResult.SAVE_FAILED;
        }
        build(creator.getWorld(), def.placement());
        register(new RouletteTable(plugin, this, def));
        return CreateResult.OK;
    }

    /** Removes the table, its blocks (those that are still what the plugin placed) and its tables.yml entry. */
    public boolean remove(String id) {
        RouletteTable table = table(id);
        if (table == null) {
            return false;
        }
        table.close();
        tables.remove(table.id());
        blocks.values().removeIf(b -> b.table() == table);
        World world = table.world();
        if (world != null) {
            demolish(world, table.placement());
        }
        store.remove(table.id());
        return true;
    }

    /** Changes one of a table's own rules. Refused while a round is running on it. */
    public SetResult set(String id, String setting, String value) {
        RouletteTable table = table(id);
        if (table == null) {
            return SetResult.UNKNOWN_TABLE;
        }
        TableRules old = table.rules();
        TableRules rules;
        try {
            String v = value.trim().toLowerCase(Locale.ROOT);
            rules = switch (setting.toLowerCase(Locale.ROOT)) {
                case "wheel" -> new TableRules(WheelType.valueOf(v.toUpperCase(Locale.ROOT)), old.minBet(), old.maxBet(),
                        old.maxPayout(), old.bettingTicks(), old.spinTicks(), old.resultTicks());
                case "min-bet" -> new TableRules(old.wheel(), Long.parseLong(v), old.maxBet(), old.maxPayout(),
                        old.bettingTicks(), old.spinTicks(), old.resultTicks());
                case "max-bet" -> new TableRules(old.wheel(), old.minBet(), Long.parseLong(v), old.maxPayout(),
                        old.bettingTicks(), old.spinTicks(), old.resultTicks());
                case "max-payout" -> new TableRules(old.wheel(), old.minBet(), old.maxBet(), Long.parseLong(v),
                        old.bettingTicks(), old.spinTicks(), old.resultTicks());
                case "betting-seconds" -> new TableRules(old.wheel(), old.minBet(), old.maxBet(), old.maxPayout(),
                        20 * seconds(v, 5), old.spinTicks(), old.resultTicks());
                case "spin-seconds" -> new TableRules(old.wheel(), old.minBet(), old.maxBet(), old.maxPayout(),
                        old.bettingTicks(), 20 * seconds(v, 3), old.resultTicks());
                case "result-seconds" -> new TableRules(old.wheel(), old.minBet(), old.maxBet(), old.maxPayout(),
                        old.bettingTicks(), old.spinTicks(), 20 * seconds(v, 1));
                default -> null;
            };
        } catch (IllegalArgumentException e) {
            return SetResult.BAD_VALUE;
        }
        if (rules == null) {
            return SetResult.UNKNOWN_SETTING;
        }
        if (table.phase() != RoundPhase.IDLE) {
            return SetResult.BUSY;
        }
        TableDef def = table.def().withRules(rules);
        if (!store.put(def)) {
            return SetResult.SAVE_FAILED;
        }
        table.reconfigure(def);
        return SetResult.OK;
    }

    private static int seconds(String value, int least) {
        int seconds = Integer.parseInt(value);
        if (seconds < least || seconds > 600) {
            throw new IllegalArgumentException("seconds");
        }
        return seconds;
    }

    private void register(RouletteTable table) {
        tables.put(table.id(), table);
        Placement p = table.placement();
        String world = table.def().world();
        for (Offset top : TableShape.top()) {
            blocks.put(new BlockKey(world, p.blockX(top.a(), top.b()), p.originY(), p.blockZ(top.a(), top.b())),
                    new TableBlock(table, -1));
        }
        for (Seat seat : TableShape.seats()) {
            Offset stool = seat.block();
            blocks.put(new BlockKey(world, p.blockX(stool.a(), stool.b()), p.originY(), p.blockZ(stool.a(), stool.b())),
                    new TableBlock(table, seat.index()));
        }
    }

    /** The table, its stools and the floor around them, three blocks high, must be free. */
    private static boolean hasRoom(World world, Placement p) {
        if (p.originY() < world.getMinHeight() || p.originY() + 2 >= world.getMaxHeight()) {
            return false;
        }
        for (int a = -1; a <= TableShape.LENGTH; a++) {
            for (int b = -1; b <= TableShape.WIDTH; b++) {
                if (!TableShape.inFootprint(a, b)) {
                    continue;
                }
                for (int dy = 0; dy <= 2; dy++) {
                    Block block = world.getBlockAt(p.blockX(a, b), p.originY() + dy, p.blockZ(a, b));
                    if (!block.isEmpty() && !block.isReplaceable()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void build(World world, Placement p) {
        Material top = material("build.table-block", Material.GREEN_TERRACOTTA);
        Material stool = material("build.stool-block", Material.DARK_OAK_STAIRS);
        for (Offset o : TableShape.top()) {
            world.getBlockAt(p.blockX(o.a(), o.b()), p.originY(), p.blockZ(o.a(), o.b())).setType(top, false);
        }
        for (Seat seat : TableShape.seats()) {
            Offset o = seat.block();
            Block block = world.getBlockAt(p.blockX(o.a(), o.b()), p.originY(), p.blockZ(o.a(), o.b()));
            BlockData data = stool.createBlockData();
            // whatever the stool is made of, its seat is the top face of the block
            if (data instanceof Stairs stairs) {
                stairs.setHalf(Bisected.Half.TOP);
                stairs.setFacing(face(seat.faceA() * p.uX() + seat.faceB() * p.vX(),
                        seat.faceA() * p.uZ() + seat.faceB() * p.vZ()));
            } else if (data instanceof Slab slab) {
                slab.setType(Slab.Type.TOP);
            }
            block.setBlockData(data, false);
        }
    }

    /** Only blocks that are still the configured table or stool material are cleared; anything else was built by someone. */
    private void demolish(World world, Placement p) {
        Material top = material("build.table-block", Material.GREEN_TERRACOTTA);
        Material stool = material("build.stool-block", Material.DARK_OAK_STAIRS);
        for (Offset o : TableShape.top()) {
            Block block = world.getBlockAt(p.blockX(o.a(), o.b()), p.originY(), p.blockZ(o.a(), o.b()));
            if (block.getType() == top) {
                block.setType(Material.AIR, false);
            }
        }
        for (Seat seat : TableShape.seats()) {
            Offset o = seat.block();
            Block block = world.getBlockAt(p.blockX(o.a(), o.b()), p.originY(), p.blockZ(o.a(), o.b()));
            if (block.getType() == stool) {
                block.setType(Material.AIR, false);
            }
        }
    }

    private Material material(String path, Material fallback) {
        String name = plugin.config().raw().getString(path, fallback.name());
        Material material = Material.matchMaterial(name == null ? "" : name);
        if (material == null || !material.isBlock() || material.isAir()) {
            plugin.getLogger().warning("config.yml " + path + ": '" + name + "' is not a block; using " + fallback + ".");
            return fallback;
        }
        return material;
    }

    private static BlockFace cardinal(float yaw) {
        int quarter = Math.floorMod(Math.round(yaw / 90f), 4);
        return switch (quarter) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    private static BlockFace face(int dx, int dz) {
        return dx > 0 ? BlockFace.EAST : dx < 0 ? BlockFace.WEST : dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }
}
