package com.vortex.roulette.game;

/** Time as the round sees it, in server ticks (20 per second). Injected so tests can drive a round without a server. */
public interface GameClock {

    /** Runs {@code action} once, {@code delayTicks} from now, on the thread that drives the round. */
    Task runLater(long delayTicks, Runnable action);

    /** A monotonic tick counter; only differences are meaningful. */
    long now();

    interface Task {
        /** Stops the action from running; harmless if it already ran. */
        void cancel();
    }
}
