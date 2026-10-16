package com.rs.lib.economy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
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
 *   <li>Offline players: every operation also takes an {@link OfflinePlayer}. Vault serves
 *   them (the economy plugin decides how). The command backend, and any backend that does not
 *   declare {@link Backend#servesOffline()}, does not: for a player who is not online
 *   {@link #give} and {@link #tryTake} are {@code false} without running anything,
 *   {@link #balance} is empty and {@link #getBalance} is 0. {@link #servesOffline()} tells which
 *   case applies before trying.</li>
 *   <li>{@link #transfer} moves money between two players: it takes from the payer first, gives
 *   to the receiver only after the take succeeded, and gives it back to the payer when the
 *   receiver could not be paid. It never creates money; its {@link TransferOutcome} says what
 *   happened.</li>
 *   <li>Amounts may also be {@link BigDecimal}. Backends work with {@code double} (Vault does), so
 *   such an amount must be exactly a {@code double} ({@code BigDecimal.valueOf(d)} gives back
 *   the same number); one that is not, such as {@code new BigDecimal(0.1)}, is refused.</li>
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

        /** The balance; NaN when it cannot be read. */
        double getBalance(OfflinePlayer player);

        /** Adds {@code amount}; the future says whether it really happened. */
        CompletableFuture<Boolean> give(OfflinePlayer player, double amount);

        /** Takes {@code amount} only when the player has it; the future says whether it was taken. */
        CompletableFuture<Boolean> tryTake(OfflinePlayer player, double amount);

        /** False while this backend cannot serve (its plugin is missing, for example). */
        default boolean available() {
            return true;
        }

        /**
         * True when this backend can read and change the money of a player who is not online.
         * False by default: the bridge then refuses offline players before calling it.
         */
        default boolean servesOffline() {
            return false;
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
        return getBalance((OfflinePlayer) player);
    }

    /**
     * The balance of {@code player}, online or not; 0 off the main thread, without a backend, on
     * failure, or when the player is offline and the backend only serves online players. Use
     * {@link #balance} to tell an unknown balance from 0.
     */
    public double getBalance(OfflinePlayer player) {
        double balance = read(player);
        return Double.isNaN(balance) ? 0 : balance;
    }

    /**
     * The balance of {@code player}, online or not, or empty when it cannot be known: off the
     * main thread, without a backend, on failure, or when the player is offline and the backend
     * only serves online players.
     */
    public Optional<BigDecimal> balance(OfflinePlayer player) {
        double balance = read(player);
        return Double.isFinite(balance) ? Optional.of(BigDecimal.valueOf(balance)) : Optional.empty();
    }

    /** Gives {@code amount} to {@code player}; the future is true only when the money was given. */
    public CompletableFuture<Boolean> give(Player player, double amount) {
        return transact(true, player, amount);
    }

    /**
     * Gives {@code amount} to {@code player}, online or not; the future is true only when the
     * money was given (always false for an offline player when the backend only serves online
     * players).
     */
    public CompletableFuture<Boolean> give(OfflinePlayer player, double amount) {
        return transact(true, player, amount);
    }

    /** {@link #give(OfflinePlayer, double)} with an amount that must be exactly a {@code double}. */
    public CompletableFuture<Boolean> give(OfflinePlayer player, BigDecimal amount) {
        return transact(true, player, toDouble(amount));
    }

    /**
     * Takes {@code amount} from {@code player} when they have it; the future is true only when
     * it was taken.
     */
    public CompletableFuture<Boolean> tryTake(Player player, double amount) {
        return transact(false, player, amount);
    }

    /**
     * Takes {@code amount} from {@code player}, online or not, when they have it; the future is
     * true only when it was taken (always false for an offline player when the backend only
     * serves online players).
     */
    public CompletableFuture<Boolean> tryTake(OfflinePlayer player, double amount) {
        return transact(false, player, amount);
    }

    /** {@link #tryTake(OfflinePlayer, double)} with an amount that must be exactly a {@code double}. */
    public CompletableFuture<Boolean> tryTake(OfflinePlayer player, BigDecimal amount) {
        return transact(false, player, toDouble(amount));
    }

    /**
     * Moves {@code amount} from {@code from} to {@code to}, either of them online or not:
     * takes it from {@code from} first, then gives it to {@code to}; when that give fails the
     * amount is given back to {@code from} ({@link TransferOutcome#REFUNDED}), and when the
     * refund fails too the outcome is {@link TransferOutcome#REFUND_FAILED}, logged at SEVERE
     * with both players and the amount for a manual refund. The future never fails. Like
     * {@link #give}, it may be called from any thread and completes on the main thread.
     */
    public CompletableFuture<TransferOutcome> transfer(OfflinePlayer from, OfflinePlayer to, double amount) {
        if (from == null || to == null || !Double.isFinite(amount) || amount <= 0
                || Objects.equals(from.getUniqueId(), to.getUniqueId())) {
            host.debug(() -> "economy transfer of " + amount + " refused: two different players and an amount above 0 are needed");
            return CompletableFuture.completedFuture(TransferOutcome.INVALID);
        }
        return onMain("transfer", () -> transferHere(from, to, amount), TransferOutcome.NO_ECONOMY);
    }

    /** {@link #transfer(OfflinePlayer, OfflinePlayer, double)} with an amount that must be exactly a {@code double}. */
    public CompletableFuture<TransferOutcome> transfer(OfflinePlayer from, OfflinePlayer to, BigDecimal amount) {
        return transfer(from, to, toDouble(amount));
    }

    /**
     * True when the economy in use right now can reach players who are not online (Vault);
     * false for the command backend, for a backend that does not declare it, or without any.
     */
    public boolean servesOffline() {
        Backend backend = firstAvailable();
        if (backend == null) {
            return false;
        }
        try {
            return backend.servesOffline();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** True when some backend can serve right now. */
    public boolean available() {
        return firstAvailable() != null;
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

    /** The balance, or NaN when it cannot be known. */
    private double read(OfflinePlayer player) {
        if (player == null) {
            return Double.NaN;
        }
        if (!host.isPrimaryThread()) {
            String site = callSite();
            warnOnce("thread:" + site, "getBalance was called off the main thread at " + site
                    + "; it returned 0. Read balances on the main thread.");
            return Double.NaN;
        }
        Backend backend = active();
        if (backend == null || !reaches(backend, player)) {
            return Double.NaN;
        }
        try {
            return backend.getBalance(player);
        } catch (RuntimeException | LinkageError e) {
            host.logger().log(Level.WARNING, "Could not read the balance of " + player.getName(), e);
            return Double.NaN;
        }
    }

    private CompletableFuture<Boolean> transact(boolean give, OfflinePlayer player, double amount) {
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
        return onMain(what, () -> runHere(give, player, amount), false);
    }

    /**
     * Runs {@code work} on the main thread: right here when this is it, otherwise after a hop
     * that completes with {@code dropped} when it cannot happen (the plugin is shutting down).
     */
    private <T> CompletableFuture<T> onMain(String what, Supplier<CompletableFuture<T>> work, T dropped) {
        if (host.isPrimaryThread()) {
            return work.get();
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        MainHops.Hop hop = MainHops.add(host.owner(), () -> result.complete(dropped));
        try {
            host.sync(() -> {
                if (hop.claim()) {
                    work.get().whenComplete((value, error) -> result.complete(error == null && value != null ? value : dropped));
                }
            });
        } catch (RuntimeException notScheduled) {
            if (hop.claim()) {
                host.logger().warning("Could not reach the main thread for an economy " + what
                        + " (the plugin is shutting down); it did not happen.");
                result.complete(dropped);
            }
        }
        return result;
    }

    /** On the main thread: never throws, never fails the future, never returns null. */
    private CompletableFuture<Boolean> runHere(boolean give, OfflinePlayer player, double amount) {
        Backend backend = active();
        if (backend == null) {
            return FALSE;
        }
        if (!reaches(backend, player)) {
            host.debug(() -> "economy " + (give ? "give" : "take") + " for " + player.getName()
                    + " refused: the player is offline and this economy only serves online players");
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

    /** On the main thread: take, give, and refund when the give failed. Never fails the future. */
    private CompletableFuture<TransferOutcome> transferHere(OfflinePlayer from, OfflinePlayer to, double amount) {
        Backend backend = active();
        if (backend == null) {
            return CompletableFuture.completedFuture(TransferOutcome.NO_ECONOMY);
        }
        if (!reaches(backend, from) || !reaches(backend, to)) {
            host.debug(() -> "economy transfer from " + from.getName() + " to " + to.getName()
                    + " refused: a player is offline and this economy only serves online players");
            return CompletableFuture.completedFuture(TransferOutcome.OFFLINE);
        }
        return transact(false, from, amount).thenCompose(taken -> {
            if (!taken) {
                return CompletableFuture.completedFuture(TransferOutcome.NOT_TAKEN);
            }
            return transact(true, to, amount).thenCompose(given -> {
                if (given) {
                    return CompletableFuture.completedFuture(TransferOutcome.DONE);
                }
                return transact(true, from, amount).thenApply(refunded -> {
                    String pair = amount + " from " + describe(from) + " to " + describe(to);
                    if (refunded) {
                        host.logger().warning("Economy transfer of " + pair + ": the receiver could not be paid,"
                                + " so the amount was given back to the payer.");
                        return TransferOutcome.REFUNDED;
                    }
                    host.logger().severe("Economy transfer of " + pair + ": the amount was taken from the payer,"
                            + " the receiver could not be paid and the refund failed too. Give " + amount
                            + " back to " + describe(from) + " by hand.");
                    return TransferOutcome.REFUND_FAILED;
                });
            });
        });
    }

    /** True when {@code backend} can serve {@code player} as they are now (online, or the backend serves offline players). */
    private boolean reaches(Backend backend, OfflinePlayer player) {
        if (player.isOnline()) {
            return true;
        }
        try {
            return backend.servesOffline();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** {@code Name (uuid)} for log lines. */
    private static String describe(OfflinePlayer player) {
        return player.getName() + " (" + player.getUniqueId() + ")";
    }

    /** {@code amount} as a double when it is exactly one; NaN (refused later) otherwise. */
    static double toDouble(BigDecimal amount) {
        if (amount == null) {
            return Double.NaN;
        }
        double value = amount.doubleValue();
        if (!Double.isFinite(value) || BigDecimal.valueOf(value).compareTo(amount) != 0) {
            return Double.NaN;
        }
        return value;
    }

    /** The first available backend, or null (no warning). */
    private Backend firstAvailable() {
        for (Backend backend : order) {
            if (isAvailable(backend)) {
                return backend;
            }
        }
        return null;
    }

    /** The first available backend, or null after one warning. */
    private Backend active() {
        Backend backend = firstAvailable();
        if (backend != null) {
            return backend;
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
