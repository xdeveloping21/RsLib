package com.rs.lib.economy;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;

/**
 * The only class of RsLib that touches Vault. It is loaded by {@link VaultBackend}'s soft
 * dependency only after Vault was found enabled with its {@code Economy} class. The provider is
 * looked up on every call (a map read): an economy plugin that registers late, or is replaced,
 * is used from its first moment.
 */
final class VaultLedger implements VaultBackend.Ledger {

    private VaultLedger() {
    }

    static VaultBackend.Ledger create() {
        return new VaultLedger();
    }

    private static Economy economy() {
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration == null ? null : registration.getProvider();
    }

    private static Economy require() {
        Economy economy = economy();
        if (economy == null) {
            throw new IllegalStateException("no economy provider is registered with Vault");
        }
        return economy;
    }

    @Override
    public boolean ready() {
        Economy economy = economy();
        return economy != null && economy.isEnabled();
    }

    @Override
    public double balance(OfflinePlayer player) {
        return require().getBalance(player);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return require().has(player, amount);
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        return succeeded(require().withdrawPlayer(player, amount));
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        return succeeded(require().depositPlayer(player, amount));
    }

    private static boolean succeeded(EconomyResponse response) {
        return response != null && response.transactionSuccess();
    }
}
