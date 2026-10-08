package com.vortex.roulette.table;

import com.vortex.roulette.game.TableRules;
import com.vortex.roulette.model.WheelType;
import java.util.Locale;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;

/**
 * A table as tables.yml remembers it: where its origin block is, which way the layout runs from the wheel, and
 * its own copy of the rules.
 */
public record TableDef(String id, String world, int x, int y, int z, BlockFace facing, TableRules rules) {

    public TableDef {
        if (!isValidId(id)) {
            throw new IllegalArgumentException("table name '" + id + "' (letters, digits, - and _ only, at most 32)");
        }
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world");
        }
        if (facing == null || Math.abs(facing.getModX()) + Math.abs(facing.getModZ()) != 1 || facing.getModY() != 0) {
            throw new IllegalArgumentException("facing must be north, east, south or west");
        }
        if (rules == null) {
            throw new IllegalArgumentException("rules");
        }
    }

    public static boolean isValidId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,32}");
    }

    public TableShape.Placement placement() {
        return new TableShape.Placement(x, y, z, facing.getModX(), facing.getModZ());
    }

    public TableDef withRules(TableRules newRules) {
        return new TableDef(id, world, x, y, z, facing, newRules);
    }

    void write(ConfigurationSection section) {
        section.set("world", world);
        section.set("x", x);
        section.set("y", y);
        section.set("z", z);
        section.set("facing", facing.name().toLowerCase(Locale.ROOT));
        section.set("wheel", rules.wheel().name().toLowerCase(Locale.ROOT));
        section.set("min-bet", rules.minBet());
        section.set("max-bet", rules.maxBet());
        section.set("max-payout", rules.maxPayout());
        section.set("betting-seconds", rules.bettingTicks() / 20);
        section.set("spin-seconds", rules.spinTicks() / 20);
        section.set("result-seconds", rules.resultTicks() / 20);
    }

    /**
     * @param fallback rules for the settings the entry leaves out
     * @throws IllegalArgumentException with the reason, if the entry cannot be a table
     */
    static TableDef read(String id, ConfigurationSection section, TableRules fallback) {
        if (!section.isInt("x") || !section.isInt("y") || !section.isInt("z")) {
            throw new IllegalArgumentException("x, y and z must be whole numbers");
        }
        BlockFace facing;
        try {
            facing = BlockFace.valueOf(String.valueOf(section.getString("facing")).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("facing must be north, east, south or west");
        }
        String wheelName = section.getString("wheel", fallback.wheel().name()).trim().toUpperCase(Locale.ROOT);
        WheelType wheel;
        try {
            wheel = WheelType.valueOf(wheelName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("wheel must be european or american");
        }
        TableRules rules;
        try {
            rules = new TableRules(wheel,
                    section.getLong("min-bet", fallback.minBet()),
                    section.getLong("max-bet", fallback.maxBet()),
                    section.getLong("max-payout", fallback.maxPayout()),
                    20 * section.getInt("betting-seconds", fallback.bettingTicks() / 20),
                    20 * section.getInt("spin-seconds", fallback.spinTicks() / 20),
                    20 * section.getInt("result-seconds", fallback.resultTicks() / 20));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("its limits or timings make no sense (" + e.getMessage() + ")");
        }
        return new TableDef(id, section.getString("world"), section.getInt("x"), section.getInt("y"),
                section.getInt("z"), facing, rules);
    }
}
