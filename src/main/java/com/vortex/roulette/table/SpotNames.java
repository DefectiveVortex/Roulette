package com.vortex.roulette.table;

import com.vortex.roulette.config.ConfigManager;
import com.vortex.roulette.model.BetSpot;
import com.vortex.roulette.model.BetType;
import com.vortex.roulette.model.Pocket;
import java.util.Locale;
import java.util.stream.Collectors;

/** What a bet spot is called in chat, the action bar and the menu (messages.yml {@code bet-name-*}). */
public final class SpotNames {

    private SpotNames() {
    }

    public static String name(ConfigManager config, BetSpot spot) {
        String key = "bet-name-" + spot.type().name().toLowerCase(Locale.ROOT).replace('_', '-');
        String numbers = spot.type() == BetType.STRAIGHT
                ? config.number(spot.pockets().get(0))
                : spot.pockets().stream().map(Pocket::label).collect(Collectors.joining("-"));
        return config.message(key, "numbers", numbers, "index", spot.index());
    }
}
