package com.vortex.roulette.display;

import com.vortex.roulette.RoulettePlugin;
import com.vortex.roulette.model.Pocket;
import com.vortex.roulette.table.TableAttachment;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.stream.Collectors;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;

/** The last numbers of one table, newest first, as floating text above the wheel that turns to face each player. */
final class NumberBoard {

    /** Blocks above the table top: over the heads of seated players' line of sight to the felt. */
    private static final double HEIGHT = 1.5;
    private static final int LONGEST = 30;

    private final RoulettePlugin plugin;
    private final Location at;
    private final Deque<Pocket> recent = new ArrayDeque<>();
    private TextDisplay display;
    private boolean shown;

    NumberBoard(RoulettePlugin plugin, Location wheelCentre) {
        this.plugin = plugin;
        this.at = wheelCentre.clone().add(0, HEIGHT, 0);
        this.at.setYaw(0);
    }

    /** Called on every wheel update as well, so it also brings the board back after the chunk was unloaded. */
    void show() {
        shown = true;
        if (size() == 0) {
            remove();
        } else if (display == null || !display.isValid()) {
            display = at.getWorld().spawn(at, TextDisplay.class, board -> {
                board.setBillboard(Display.Billboard.VERTICAL);
                board.setBrightness(new Display.Brightness(15, 15));
                board.setPersistent(false);
                board.addScoreboardTag(TableAttachment.ENTITY_TAG);
            });
            write();
        }
    }

    void hide() {
        shown = false;
        remove();
    }

    void add(Pocket pocket) {
        recent.addFirst(pocket);
        while (recent.size() > LONGEST) {
            recent.removeLast();
        }
        if (shown && at.isWorldLoaded() && at.isChunkLoaded()) {
            show();
            write();
        }
    }

    private int size() {
        return Math.clamp(plugin.config().raw().getInt("wheel.board-numbers", 10), 0, LONGEST);
    }

    private void write() {
        if (display == null || !display.isValid()) {
            return;
        }
        String numbers = recent.stream().limit(size())
                .map(pocket -> plugin.config().message("board-" + pocket.color().name().toLowerCase(Locale.ROOT),
                        "number", pocket.label()))
                .collect(Collectors.joining(" "));
        String text = plugin.config().message("board-title") + "\n"
                + (numbers.isEmpty() ? plugin.config().message("board-empty") : numbers);
        display.text(LegacyComponentSerializer.legacySection().deserialize(text));
    }

    private void remove() {
        if (display != null) {
            display.remove();
            display = null;
        }
    }
}
