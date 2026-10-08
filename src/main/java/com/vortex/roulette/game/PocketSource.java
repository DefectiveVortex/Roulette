package com.vortex.roulette.game;

import com.vortex.roulette.model.Pocket;
import com.vortex.roulette.model.WheelType;
import java.security.SecureRandom;

/** Where a round's result comes from. Production uses {@link #secure()}; tests script it. */
@FunctionalInterface
public interface PocketSource {

    Pocket draw(WheelType wheel);

    /** Uniform over the wheel's pockets, from a {@link SecureRandom}. */
    static PocketSource secure() {
        SecureRandom random = new SecureRandom();
        return wheel -> wheel.pockets().get(random.nextInt(wheel.size()));
    }
}
