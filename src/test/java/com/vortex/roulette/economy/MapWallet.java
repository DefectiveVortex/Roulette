package com.vortex.roulette.economy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** An economy in a map, which can be told to refuse. */
final class MapWallet implements Wallet {
    final Map<UUID, Long> balances = new HashMap<>();
    boolean available = true;
    boolean refuseDeposits;
    boolean refuseWithdrawals;

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public boolean withdraw(UUID player, long amount) {
        if (!available || refuseWithdrawals || amount <= 0 || balance(player) < amount) {
            return false;
        }
        balances.merge(player, -amount, Long::sum);
        return true;
    }

    @Override
    public boolean deposit(UUID player, long amount) {
        if (!available || refuseDeposits || amount <= 0) {
            return false;
        }
        balances.merge(player, amount, Long::sum);
        return true;
    }

    @Override
    public long balance(UUID player) {
        return available ? balances.getOrDefault(player, 0L) : 0;
    }

    @Override
    public String name() {
        return "MapWallet";
    }
}
