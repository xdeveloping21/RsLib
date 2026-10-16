package com.rs.lib.economy;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.hook.SoftDependency;

/**
 * Money through Vault. This class never names a Vault type: everything Vault goes through a
 * {@link Ledger}, whose only real implementation ({@link VaultLedger}) is loaded by the soft
 * dependency's factory after Vault was found enabled. Without Vault, or before an economy
 * plugin registered its provider, the backend is simply unavailable.
 */
final class VaultBackend implements EconomyBridge.Backend {

    /** Vault's economy, in types every server has. */
    interface Ledger {

        /** False while no economy plugin has registered a provider with Vault. */
        boolean ready();

        double balance(OfflinePlayer player);

        boolean has(OfflinePlayer player, double amount);

        /** True when the provider reports the withdrawal as done. */
        boolean withdraw(OfflinePlayer player, double amount);

        /** True when the provider reports the deposit as done. */
        boolean deposit(OfflinePlayer player, double amount);
    }

    private static final CompletableFuture<Boolean> TRUE = CompletableFuture.completedFuture(true);
    private static final CompletableFuture<Boolean> FALSE = CompletableFuture.completedFuture(false);

    private final Supplier<Ledger> ledger;
    private final Logger logger;

    VaultBackend(Supplier<Ledger> ledger, Logger logger) {
        this.ledger = ledger;
        this.logger = logger;
    }

    /** The backend of {@code owner}: Vault through a soft dependency that owner's disable switches off. */
    static VaultBackend of(JavaPlugin owner, EconomyHost host) {
        SoftDependency<Ledger> vault = SoftDependency.of(owner, "Vault", VaultLedger::create)
                .requiresClass("net.milkbowl.vault.economy.Economy");
        return new VaultBackend(() -> {
            Optional<Ledger> found = vault.get();
            if (found.isEmpty()) {
                // The economy plugin may register its provider later: look again next time.
                vault.invalidate();
                return null;
            }
            return found.get();
        }, host.logger());
    }

    @Override
    public boolean available() {
        Ledger current = ledger.get();
        return current != null && current.ready();
    }

    /** Vault's API takes offline players; the economy plugin decides how it serves them. */
    @Override
    public boolean servesOffline() {
        return true;
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        Ledger current = ledger.get();
        if (current == null) {
            return Double.NaN;
        }
        try {
            return current.balance(player);
        } catch (RuntimeException | LinkageError e) {
            logger.log(Level.WARNING, "Vault could not read the balance of " + player.getName(), e);
            return Double.NaN;
        }
    }

    @Override
    public CompletableFuture<Boolean> give(OfflinePlayer player, double amount) {
        Ledger current = ledger.get();
        if (current == null) {
            return FALSE;
        }
        try {
            return current.deposit(player, amount) ? TRUE : FALSE;
        } catch (RuntimeException | LinkageError e) {
            logger.log(Level.WARNING, "Vault could not give " + amount + " to " + player.getName(), e);
            return FALSE;
        }
    }

    @Override
    public CompletableFuture<Boolean> tryTake(OfflinePlayer player, double amount) {
        Ledger current = ledger.get();
        if (current == null) {
            return FALSE;
        }
        try {
            if (!current.has(player, amount)) {
                return FALSE;
            }
            return current.withdraw(player, amount) ? TRUE : FALSE;
        } catch (RuntimeException | LinkageError e) {
            logger.log(Level.WARNING, "Vault could not take " + amount + " from " + player.getName(), e);
            return FALSE;
        }
    }
}
