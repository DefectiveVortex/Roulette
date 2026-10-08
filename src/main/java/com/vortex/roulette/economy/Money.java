package com.vortex.roulette.economy;

import com.vortex.roulette.RoulettePlugin;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Level;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;

/**
 * Sets the plugin's money up and takes it down again: opens the stake journal, returns what a crash left behind,
 * installs the bank, and hands over anything still owed when the economy appears or the player joins.
 *
 * <p>{@code onEnable}: {@code money = Money.install(this)}. {@code onDisable}: abort every round first (that refunds
 * through the bank), then {@code money.shutdown()}.
 */
public final class Money implements Listener {
    /** Long enough after a join for an economy to have loaded the account. */
    private static final long JOIN_DELAY_TICKS = 40;

    private final RoulettePlugin plugin;
    private final VaultWallet wallet;
    private final JournaledBank bank;

    private Money(RoulettePlugin plugin, VaultWallet wallet, JournaledBank bank) {
        this.plugin = plugin;
        this.wallet = wallet;
        this.bank = bank;
    }

    public static Money install(RoulettePlugin plugin) {
        VaultWallet wallet = new VaultWallet(plugin.getLogger());
        Path file = plugin.getDataFolder().toPath().resolve("stakes.journal");
        JournaledBank bank = null;
        try {
            bank = new JournaledBank(wallet, StakeJournal.open(file, plugin.getLogger()), plugin.getLogger());
            bank.recover();
            bank.setOwedListener(new JournaledBank.OwedListener() {
                @Override
                public void owed(UUID player, long amount) {
                    tell(plugin, player, "money-kept", amount);
                }

                @Override
                public void delivered(UUID player, long amount) {
                    tell(plugin, player, "money-returned", amount);
                }
            });
            plugin.setBank(bank);
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Cannot open the stake journal " + file
                    + ". Roulette tables take no bets until this is fixed and the server restarted.", e);
            plugin.setBank(new NoBets(wallet));
        }
        Money money = new Money(plugin, wallet, bank);
        Bukkit.getPluginManager().registerEvents(money, plugin);
        // One tick on, every plugin has enabled and the economy is registered if there is one.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!wallet.available()) {
                plugin.getLogger().warning("No economy is registered with Vault. Roulette tables take no bets until one is.");
            } else if (money.bank != null) {
                money.bank.deliverAll();
            }
        });
        return money;
    }

    private static void tell(RoulettePlugin plugin, UUID player, String key, long amount) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            String message = plugin.config().prefixed(key, "amount", plugin.config().money(amount));
            if (!message.isEmpty()) {
                online.sendMessage(message);
            }
        }
    }

    /** The journaled bank, or null if the journal could not be opened (the tables then take no bets). */
    public JournaledBank bank() {
        return bank;
    }

    public VaultWallet wallet() {
        return wallet;
    }

    /** Call after the rounds were aborted: forces the journal to disk and closes it. */
    public void shutdown() {
        HandlerList.unregisterAll(this);
        if (bank != null) {
            bank.journal().close();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        if (bank != null && bank.journal().owed(player) > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> bank.deliver(player), JOIN_DELAY_TICKS);
        }
    }

    @EventHandler
    public void onEconomyRegistered(ServiceRegisterEvent event) {
        if (event.getProvider().getService() == Economy.class) {
            wallet.reset();
            if (bank != null && plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, bank::deliverAll);
            }
        }
    }

    @EventHandler
    public void onEconomyUnregistered(ServiceUnregisterEvent event) {
        if (event.getProvider().getService() == Economy.class) {
            wallet.reset();
        }
    }

    /** The bank of a server whose journal cannot be opened: balances can be read, nothing can be staked. */
    private record NoBets(Wallet wallet) implements Bank {
        @Override
        public boolean reserve(UUID roundId, UUID player, long amount) {
            return false;
        }

        @Override
        public void refund(UUID roundId, UUID player, long amount) {}

        @Override
        public void pay(UUID roundId, UUID player, long staked, long payout) {}

        @Override
        public long balance(UUID player) {
            return wallet.balance(player);
        }
    }
}
