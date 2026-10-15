package com.rs.lib.economy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import com.rs.lib.Rs;
import com.rs.lib.db.internal.MainHops;

/**
 * Money, reached with {@code rs().economy()}: Vault when an economy plugin is there, otherwise
 * a backend that runs console commands and reads the balance with a placeholder
 * ({@link #useCommandBackend}), or any backend the plugin registers.
 *
 * <pre>
 * rs().economy().tryTake(player, price).thenAccept(paid -&gt; {
 *     if (paid) { ... }
 * });
 * </pre>
 *
 * <ul>
 *   <li>Every future carries the real outcome of the transaction, never an assumed success.</li>
 *   <li>Economy runs on the main thread (the global region on Folia). {@link #give} and
 *   {@link #tryTake} may be called from any thread: off it they hop there and complete there.
 *   {@link #getBalance} off the main thread returns 0 and warns once per calling line.</li>
 *   <li>An amount that is not a finite number above 0 is refused ({@code false}).</li>
 *   <li>Backends are tried in the order they were added (Vault first) and the first one that
 *   is {@linkplain Backend#available() available} is used. Replacing a name keeps its place.</li>
 *   <li>Without any backend every operation fails ({@code false}, balance 0) with one warning.</li>
 * </ul>
 *
 * <p>Vault is optional: no class that is always loaded refers to Vault's types, and an economy
 * provider registered late is found on the next call.</p>
 */
public final class EconomyBridge {

    /** Name of the Vault backend. */
    public static final String VAULT = "vault";
    /** Name of the backend {@link #useCommandBackend} adds. */
    public static final String COMMAND = "command";

    /** Somewhere money lives. Called on the main thread by the bridge, with a valid amount. */
    public interface Backend {

        double getBalance(OfflinePlayer player);

        /** Adds {@code amount}; the future says whether it really happened. */
        CompletableFuture<Boolean> give(OfflinePlayer player, double amount);

        /** Takes {@code amount} only when the player has it; the future says whether it was taken. */
        CompletableFuture<Boolean> tryTake(OfflinePlayer player, double amount);

        /** False while this backend cannot serve (its plugin is missing, for example). */
        default boolean available() {
            return true;
        }
    }

    private static final CompletableFuture<Boolean> FALSE = CompletableFuture.completedFuture(false);

    private final EconomyHost host;
    /** Guarded by itself; read through {@link #order}. */
    private final Map<String, Backend> backends = new LinkedHashMap<>();
    private volatile Backend[] order = new Backend[0];
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /** Created by the context, with the Vault backend in first place. */
    public EconomyBridge(Rs rs) {
        this(EconomyHost.of(Objects.requireNonNull(rs, "rs")));
        registerBackend(VAULT, VaultBackend.of(rs.plugin(), host));
    }

    EconomyBridge(EconomyHost host) {
        this.host = host;
    }

    /** The balance of {@code player}; 0 off the main thread, without a backend or on failure. */
    public double getBalance(Player player) {
        if (player == null) {
            return 0;
        }
        if (!host.isPrimaryThread()) {
            String site = callSite();
            warnOnce("thread:" + site, "getBalance was called off the main thread at " + site
                    + "; it returned 0. Read balances on the main thread.");
            return 0;
        }
        Backend backend = active();
        if (backend == null) {
            return 0;
        }
        try {
            double balance = backend.getBalance(player);
            return Double.isNaN(balance) ? 0 : balance;
        } catch (RuntimeException | LinkageError e) {
            host.logger().log(Level.WARNING, "Could not read the balance of " + player.getName(), e);
            return 0;
        }
    }

    /** Gives {@code amount} to {@code player}; the future is true only when the money was given. */
    public CompletableFuture<Boolean> give(Player player, double amount) {
        return transact(true, player, amount);
    }

    /**
     * Takes {@code amount} from {@code player} when they have it; the future is true only when
     * it was taken.
     */
    public CompletableFuture<Boolean> tryTake(Player player, double amount) {
        return transact(false, player, amount);
    }

    /** True when some backend can serve right now. */
    public boolean available() {
        for (Backend backend : order) {
            if (isAvailable(backend)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds a backend after the ones already there, or replaces the backend with the same name
     * in its place.
     */
    public void registerBackend(String name, Backend backend) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(backend, "backend");
        synchronized (backends) {
            backends.put(name, backend);
            order = backends.values().toArray(new Backend[0]);
        }
    }

    /**
     * Money through console commands when there is no Vault: {@code giveCommand} and
     * {@code takeCommand} take {@code %player%} and {@code %amount%} (a plain decimal such as
     * {@code 1500} or {@code 2.5}); the balance is read from {@code balancePlaceholder}
     * (PlaceholderAPI), for example {@code %vault_eco_balance%}. A take only succeeds when the
     * balance went down by the amount.
     */
    public void useCommandBackend(String giveCommand, String takeCommand, String balancePlaceholder) {
        registerBackend(COMMAND, new CommandBackend(host, giveCommand, takeCommand, balancePlaceholder));
    }

    // ---------------------------------------------------------------- internals

    private CompletableFuture<Boolean> transact(boolean give, Player player, double amount) {
        String what = give ? "give" : "tryTake";
        if (player == null) {
            host.debug(() -> "economy " + what + " refused: no player");
            return FALSE;
        }
        if (!Double.isFinite(amount) || amount <= 0) {
            host.debug(() -> "economy " + what + " of " + amount + " to " + player.getName()
                    + " refused: the amount must be a number above 0");
            return FALSE;
        }
        if (host.isPrimaryThread()) {
            return runHere(give, player, amount);
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        MainHops.Hop hop = MainHops.add(host.owner(), () -> result.complete(false));
        try {
            host.sync(() -> {
                if (hop.claim()) {
                    runHere(give, player, amount).whenComplete((ok, error) -> result.complete(Boolean.TRUE.equals(ok)));
                }
            });
        } catch (RuntimeException notScheduled) {
            if (hop.claim()) {
                host.logger().warning("Could not reach the main thread for an economy " + what
                        + " (the plugin is shutting down); it did not happen.");
                result.complete(false);
            }
        }
        return result;
    }

    /** On the main thread: never throws, never fails the future, never returns null. */
    private CompletableFuture<Boolean> runHere(boolean give, Player player, double amount) {
        Backend backend = active();
        if (backend == null) {
            return FALSE;
        }
        CompletableFuture<Boolean> future;
        try {
            future = give ? backend.give(player, amount) : backend.tryTake(player, amount);
        } catch (RuntimeException | LinkageError e) {
            host.logger().log(Level.WARNING, "An economy " + (give ? "give" : "take") + " for "
                    + player.getName() + " failed", e);
            return FALSE;
        }
        if (future == null) {
            return FALSE;
        }
        return future.handle((ok, error) -> {
            if (error != null) {
                host.logger().log(Level.WARNING, "An economy " + (give ? "give" : "take") + " for "
                        + player.getName() + " failed", error);
                return false;
            }
            return Boolean.TRUE.equals(ok);
        });
    }

    /** The first available backend, or null after one warning. */
    private Backend active() {
        for (Backend backend : order) {
            if (isAvailable(backend)) {
                return backend;
            }
        }
        warnOnce("none", "No economy is available: install Vault and an economy plugin. Money"
                + " operations fail until there is one.");
        return null;
    }

    private boolean isAvailable(Backend backend) {
        try {
            return backend.available();
        } catch (RuntimeException | LinkageError e) {
            warnOnce("available:" + backend.getClass().getName(), "The economy backend "
                    + backend.getClass().getName() + " failed to say whether it is available: " + e);
            return false;
        }
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) {
            host.logger().warning(message);
        }
    }

    /** {@code class#method:line} of the first frame outside this class. */
    static String callSite() {
        String self = EconomyBridge.class.getName();
        return StackWalker.getInstance().walk(frames -> frames
                .filter(f -> !f.getClassName().equals(self))
                .findFirst()
                .map(f -> f.getClassName() + "#" + f.getMethodName() + ":" + f.getLineNumber())
                .orElse("unknown"));
    }
}
