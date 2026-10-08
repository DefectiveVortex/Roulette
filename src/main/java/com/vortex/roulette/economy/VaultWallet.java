package com.vortex.roulette.economy;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Whatever economy is registered with Vault. The provider is looked up when first needed and again after it
 * changes ({@link #reset}), so an economy that registers after this plugin enabled is found.
 */
public final class VaultWallet implements Wallet {
    private final Logger log;
    private Economy economy;

    public VaultWallet(Logger log) {
        this.log = log;
    }

    /** Forget the provider; the next call looks it up again. */
    public void reset() {
        economy = null;
    }

    private Economy economy() {
        if (economy == null) {
            RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
            economy = registration == null ? null : registration.getProvider();
        }
        return economy;
    }

    @Override
    public boolean available() {
        Economy eco = economy();
        try {
            return eco != null && eco.isEnabled();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public boolean withdraw(UUID player, long amount) {
        Economy eco = economy();
        if (eco == null || amount <= 0) {
            return false;
        }
        try {
            return eco.withdrawPlayer(offline(player), amount).transactionSuccess();
        } catch (RuntimeException | LinkageError e) {
            log.log(Level.WARNING, eco.getName() + " failed to withdraw " + amount + " from " + player, e);
            return false;
        }
    }

    @Override
    public boolean deposit(UUID player, long amount) {
        Economy eco = economy();
        if (eco == null || amount <= 0) {
            return false;
        }
        try {
            return eco.depositPlayer(offline(player), amount).transactionSuccess();
        } catch (RuntimeException | LinkageError e) {
            log.log(Level.WARNING, eco.getName() + " failed to deposit " + amount + " to " + player, e);
            return false;
        }
    }

    @Override
    public long balance(UUID player) {
        Economy eco = economy();
        if (eco == null) {
            return 0;
        }
        try {
            return (long) Math.floor(eco.getBalance(offline(player)));
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    @Override
    public String name() {
        Economy eco = economy();
        try {
            return eco == null ? "no economy" : eco.getName();
        } catch (RuntimeException | LinkageError e) {
            return "economy";
        }
    }

    /** The economy's own way of writing an amount, e.g. "$1,000.00". */
    public String format(long amount) {
        Economy eco = economy();
        try {
            return eco == null ? Long.toString(amount) : eco.format(amount);
        } catch (RuntimeException | LinkageError e) {
            return Long.toString(amount);
        }
    }

    private static OfflinePlayer offline(UUID player) {
        return Bukkit.getOfflinePlayer(player);
    }
}
