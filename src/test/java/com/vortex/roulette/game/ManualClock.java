package com.vortex.roulette.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A {@link GameClock} that only moves when a test says so. */
public final class ManualClock implements GameClock {
    private final List<Scheduled> queue = new ArrayList<>();
    private long now;
    private long sequence;

    private record Scheduled(long due, long order, Runnable action) {}

    @Override
    public Task runLater(long delayTicks, Runnable action) {
        Scheduled scheduled = new Scheduled(now + delayTicks, sequence++, action);
        queue.add(scheduled);
        return () -> queue.remove(scheduled);
    }

    @Override
    public long now() {
        return now;
    }

    /** Moves time forward, running everything that falls due, in order, including what those actions schedule. */
    public void advance(long ticks) {
        long target = now + ticks;
        while (true) {
            Scheduled next = queue.stream()
                    .filter(s -> s.due <= target)
                    .min(Comparator.comparingLong(Scheduled::due).thenComparingLong(Scheduled::order))
                    .orElse(null);
            if (next == null) {
                break;
            }
            queue.remove(next);
            now = next.due;
            next.action.run();
        }
        now = target;
    }

    public int pending() {
        return queue.size();
    }
}
