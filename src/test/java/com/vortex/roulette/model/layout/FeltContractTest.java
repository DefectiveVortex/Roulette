package com.vortex.roulette.model.layout;

import com.google.gson.JsonObject;
import com.vortex.roulette.ArtContract;
import com.vortex.roulette.model.BetSpot;
import com.vortex.roulette.model.BetSpots;
import com.vortex.roulette.model.BetType;
import com.vortex.roulette.model.Pocket;
import com.vortex.roulette.model.WheelType;
import com.vortex.roulette.model.layout.FeltLayout.Placed;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The felt pictures are drawn from resourcepack/contract.json and clicks are matched by {@link FeltLayout}. If the
 * two disagree a chip lands on a different bet than the one painted under it, so every cell is compared here.
 */
class FeltContractTest {

    private static final JsonObject FELT = ArtContract.load().getAsJsonObject("felt");

    @Test
    void theGridIsTheContractsGrid() {
        assertArrayEquals(new double[] {FeltLayout.CANVAS_WIDTH, FeltLayout.CANVAS_HEIGHT},
                ArtContract.numbers(FELT.getAsJsonArray("canvas_cells")));
        assertArrayEquals(new double[] {FeltLayout.CANVAS_GRID_U, FeltLayout.CANVAS_GRID_V},
                ArtContract.numbers(FELT.getAsJsonArray("grid_origin_cells")));
        assertArrayEquals(new double[] {FeltLayout.WIDTH, FeltLayout.HEIGHT},
                ArtContract.numbers(FELT.getAsJsonArray("grid_cells")));
        // the grid sits in the middle of the canvas, which is what lets the table centre the felt on its blocks
        assertEquals(FeltLayout.CANVAS_WIDTH, 2 * FeltLayout.CANVAS_GRID_U + FeltLayout.WIDTH);
        assertEquals(FeltLayout.CANVAS_HEIGHT, 2 * FeltLayout.CANVAS_GRID_V + FeltLayout.HEIGHT);
    }

    @Test
    void everyCellBetLiesOnItsPaintedCell() {
        JsonObject cells = FELT.getAsJsonObject("cells");
        for (WheelType wheel : WheelType.values()) {
            FeltLayout layout = FeltLayout.of(wheel);
            BetSpots spots = BetSpots.of(wheel);
            Set<BetSpot> checked = new HashSet<>();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : cells.entrySet()) {
                BetSpot spot = spotNamed(spots, entry.getKey());
                assertCell(layout, spot, ArtContract.numbers(entry.getValue().getAsJsonArray()), wheel + " " + entry.getKey());
                checked.add(spot);
            }
            JsonObject zeros = FELT.getAsJsonObject("zero_cells")
                    .getAsJsonObject(wheel.name().toLowerCase(Locale.ROOT));
            for (Map.Entry<String, com.google.gson.JsonElement> entry : zeros.entrySet()) {
                BetSpot spot = spots.straight(Pocket.parse(entry.getKey()));
                assertCell(layout, spot, ArtContract.numbers(entry.getValue().getAsJsonArray()), wheel + " " + entry.getKey());
                checked.add(spot);
            }
            // and the contract leaves out no cell the layout has
            for (Placed placed : layout.spots()) {
                if (!placed.onLine()) {
                    assertTrue(checked.contains(placed.spot()), wheel + ": the contract has no cell for " + placed.spot());
                }
            }
        }
    }

    private static void assertCell(FeltLayout layout, BetSpot spot, double[] cell, String what) {
        Placed placed = layout.find(spot).orElseThrow(() -> new AssertionError(what + " is not on the felt"));
        assertEquals(new FeltRect(cell[0], cell[1], cell[2], cell[3]), placed.cell(), what);
        assertEquals(placed.cell().centre(), placed.anchor(), what);
    }

    private static BetSpot spotNamed(BetSpots spots, String name) {
        if (name.startsWith("column_")) {
            return spots.column(Integer.parseInt(name.substring(7)));
        }
        if (name.startsWith("dozen_")) {
            return spots.dozen(Integer.parseInt(name.substring(6)));
        }
        if (Character.isDigit(name.charAt(0))) {
            return spots.straight(Pocket.parse(name));
        }
        return spots.evenMoney(BetType.valueOf(name.toUpperCase(Locale.ROOT)));
    }
}
