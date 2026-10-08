package com.vortex.roulette.game;

import com.vortex.roulette.economy.Bank;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** An in-memory {@link Bank} that also checks the round keeps its side of the contract. */
public final class FakeBank implements Bank {
    private final Map<UUID, Long> balances = new HashMap<>();
    private final Map<String, Long> reserved = new HashMap<>();

    public void deposit(UUID player, long amount) {
        balances.merge(player, amount, Long::sum);
    }

    /** Everything currently held for unsettled rounds. */
    public long reservedTotal() {
        return reserved.values().stream().mapToLong(Long::longValue).sum();
    }

    @Override
    public boolean reserve(UUID roundId, UUID player, long amount) {
        if (amount <= 0 || balance(player) < amount) {
            return false;
        }
        balances.merge(player, -amount, Long::sum);
        reserved.merge(key(roundId, player), amount, Long::sum);
        return true;
    }

    @Override
    public void refund(UUID roundId, UUID player, long amount) {
        long held = reserved.getOrDefault(key(roundId, player), 0L);
        if (amount <= 0 || amount > held) {
            throw new IllegalStateException("refund of " + amount + " but only " + held + " reserved");
        }
        release(roundId, player, held - amount);
        balances.merge(player, amount, Long::sum);
    }

    @Override
    public void pay(UUID roundId, UUID player, long staked, long payout) {
        long held = reserved.getOrDefault(key(roundId, player), 0L);
        if (staked != held || payout < 0) {
            throw new IllegalStateException("pay with staked " + staked + " but " + held + " reserved");
        }
        release(roundId, player, 0);
        balances.merge(player, payout, Long::sum);
    }

    @Override
    public long balance(UUID player) {
        return balances.getOrDefault(player, 0L);
    }

    private void release(UUID roundId, UUID player, long left) {
        if (left == 0) {
            reserved.remove(key(roundId, player));
        } else {
            reserved.put(key(roundId, player), left);
        }
    }

    private static String key(UUID roundId, UUID player) {
        return roundId + "/" + player;
    }
}
