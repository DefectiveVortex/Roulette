package com.vortex.roulette.economy;

import java.util.UUID;

/**
 * The economy as the bank needs it: whole units in and out of one player's balance. {@link VaultWallet} is the real
 * one; tests use a map. An implementation never throws: a call that could not be carried out returns false.
 */
public interface Wallet {

    /** False while no economy is registered; nothing can be taken or given then. */
    boolean available();

    /** Takes {@code amount} from the player. False = refused, nothing was taken. */
    boolean withdraw(UUID player, long amount);

    /** Gives {@code amount} to the player, online or not. False = refused, nothing was given. */
    boolean deposit(UUID player, long amount);

    /** The balance rounded down to whole units; 0 if unavailable. */
    long balance(UUID player);

    /** The economy's name for logs and diagnostics. */
    String name();
}
