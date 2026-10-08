package com.vortex.roulette.gui;

import com.google.gson.JsonElement;
import com.vortex.roulette.ArtContract;
import com.vortex.roulette.model.BetSpot;
import com.vortex.roulette.model.BetSpots;
import com.vortex.roulette.model.WheelType;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BetMenuIconsTest {

    /** Every bet the menu offers has an icon in the pack, and the pack has no icon the menu never shows. */
    @Test
    void menuIconsAreTheContractsIcons() {
        Set<String> inPack = new HashSet<>();
        for (JsonElement file : ArtContract.load().getAsJsonObject("menu").getAsJsonArray("files")) {
            inPack.add(file.getAsString());
        }
        Set<String> used = new HashSet<>();
        for (WheelType wheel : WheelType.values()) {
            int offered = 0;
            for (BetSpot spot : BetSpots.of(wheel).all()) {
                if (spot.pockets().size() == 1 || !spot.type().isInside()) {
                    String icon = BetMenu.iconName(spot);
                    assertTrue(inPack.contains(icon), "no icon " + icon + " for " + spot);
                    used.add(icon);
                    offered++;
                }
            }
            assertEquals(wheel.hasDoubleZero() ? 50 : 49, offered);
        }
        assertEquals(inPack, used);
    }
}
