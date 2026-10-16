package com.rs.lib.papi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * PlaceholderAPI for one plugin, safe when PlaceholderAPI is not installed.
 *
 * <p>No PlaceholderAPI type is referenced here: they live in {@code PapiBridge}, loaded only
 * once PlaceholderAPI is found enabled. If it turns out incompatible ({@link LinkageError}),
 * this module warns once and behaves as if PlaceholderAPI were missing until
 * {@link #invalidate()}.</p>
 *
 * <p>{@link #apply} resolves only on the main thread, so async code never runs other plugins'
 * expansions by accident; off the main thread the tokens are left as written (and a trace line
 * says so). {@link #applyHere} resolves on any thread, for places such as Paper's async chat
 * where that is expected. A null viewer resolves against the server. Text without {@code %}
 * comes back as the same instance.</p>
 *
 * <p>{@link #expansion(String)} declares this plugin's own placeholders. Expansions are kept
 * here: one declared before PlaceholderAPI is enabled is registered as soon as it is, and all
 * are unregistered when the plugin is disabled.</p>
 */
public final class RsPapi {

    private final PapiHost host;
    /** This plugin's expansions by lower-case identifier; guarded by itself. */
    private final Map<String, Registered> expansions = new LinkedHashMap<>();
    /** Completes the futures of {@link #applyOnMain} whose task may never run. */
    private final Set<Runnable> waiting = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean retryQueued = new AtomicBoolean();
    /** Expansions already told that they wait for PlaceholderAPI, so each says it once. */
    private final Set<String> toldWaiting = ConcurrentHashMap.newKeySet();
    private volatile boolean broken;

    public RsPapi(JavaPlugin plugin) {
        this(PapiHost.of(plugin));
    }

    RsPapi(PapiHost host) {
        this.host = host;
    }

    /** True when PlaceholderAPI is installed, enabled and usable. */
    public boolean available() {
        return !broken && host.papiEnabled();
    }

    /**
     * Forgets whether PlaceholderAPI is present (RsLib calls it when PlaceholderAPI is enabled or
     * disabled). On the next tick, expansions that are not registered are registered again.
     */
    public void invalidate() {
        broken = false;
        host.forget();
        boolean any;
        synchronized (expansions) {
            any = !expansions.isEmpty();
        }
        if (any) {
            queueRetry();
        }
    }

    /** Resolves {@code %...%} tokens on the main thread; elsewhere returns the text unchanged. */
    public String apply(Player viewer, String text) {
        if (text == null || text.indexOf('%') < 0) {
            return text;
        }
        if (!host.isPrimaryThread()) {
            leftOffMain(text);
            return text;
        }
        return applyHere(viewer, text);
    }

    /** Line by line, like {@link #apply(Player, String)}; off the main thread the same list comes back. */
    public List<String> apply(Player viewer, List<String> lines) {
        if (lines == null) {
            return null;
        }
        if (!host.isPrimaryThread()) {
            leftOffMain(lines);
            return lines;
        }
        return applyHere(viewer, lines);
    }

    /** Resolves on the current thread. If an expansion throws, the text comes back unchanged. */
    public String applyHere(Player viewer, String text) {
        if (text == null || text.indexOf('%') < 0 || !available()) {
            return text;
        }
        try {
            return host.setPlaceholders(viewer, text);
        } catch (LinkageError incompatible) {
            markBroken(incompatible);
            return text;
        } catch (RuntimeException expansionFailed) {
            return text;
        }
    }

    /** Line by line, like {@link #applyHere(Player, String)}; always a new list. */
    public List<String> applyHere(Player viewer, List<String> lines) {
        if (lines == null) {
            return null;
        }
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(applyHere(viewer, line));
        }
        return out;
    }

    /**
     * Resolves on the main thread from any thread: at once when already there, otherwise on the
     * next tick. Never fails: if the plugin is disabled first, the future gets the text as written.
     */
    public CompletableFuture<String> applyOnMain(Player viewer, String text) {
        if (host.isPrimaryThread() || text == null || text.indexOf('%') < 0 || !available()) {
            return CompletableFuture.completedFuture(applyHere(viewer, text));
        }
        return onMain(text, () -> applyHere(viewer, text));
    }

    /** Line by line, like {@link #applyOnMain(Player, String)}. */
    public CompletableFuture<List<String>> applyOnMain(Player viewer, List<String> lines) {
        if (host.isPrimaryThread() || lines == null || !available()) {
            return CompletableFuture.completedFuture(applyHere(viewer, lines));
        }
        return onMain(lines, () -> applyHere(viewer, lines));
    }

    /**
     * Starts an expansion with the identifier {@code identifier} ({@code %<identifier>_...%}).
     * Nothing is registered until {@link ExpansionBuilder#register()}.
     */
    public ExpansionBuilder expansion(String identifier) {
        return new ExpansionBuilder(this::register, identifier, host.defaultAuthor(), host.defaultVersion(),
                host.logger());
    }

    /** Unregisters every expansion of this plugin (RsLib does it when the plugin is disabled). */
    public void unregisterAll() {
        List<Registered> all;
        synchronized (expansions) {
            all = new ArrayList<>(expansions.values());
            expansions.clear();
        }
        for (Registered entry : all) {
            Object handle = entry.handle;
            entry.handle = null;
            if (handle == null || !available()) {
                continue;
            }
            try {
                host.unregister(handle);
            } catch (RuntimeException | LinkageError e) {
                host.logger().log(Level.WARNING, "Could not unregister placeholder expansion '"
                        + entry.holder.identifier() + "'", e);
            }
        }
    }

    /** Takes the module down: expansions are unregistered and pending {@link #applyOnMain} futures complete. */
    public void shutdown() {
        unregisterAll();
        for (Runnable fallback : waiting) {
            fallback.run();
        }
        waiting.clear();
    }

    /** Keeps {@code holder} and registers it now if possible; called by {@link ExpansionBuilder#register()}. */
    boolean register(PapiHolder holder) {
        Registered entry = new Registered(holder);
        synchronized (expansions) {
            expansions.put(holder.key(), entry);
        }
        if (!host.isPrimaryThread()) {
            queueRetry();
            return false;
        }
        return tryRegister(entry);
    }

    private boolean tryRegister(Registered entry) {
        if (!available()) {
            if (!host.papiEnabled() && toldWaiting.add(entry.holder.key())) {
                host.logger().info("PlaceholderAPI not installed; '" + entry.holder.identifier()
                        + "' will be registered when it is enabled.");
            }
            return false;
        }
        try {
            Object handle = host.register(entry.holder);
            entry.handle = handle;
            if (handle == null) {
                host.logger().warning("PlaceholderAPI refused the placeholder expansion '"
                        + entry.holder.identifier() + "'.");
            }
            return handle != null;
        } catch (RuntimeException | LinkageError e) {
            // A LinkageError here (an old PlaceholderAPI) does not stop resolving other tokens.
            host.logger().log(Level.WARNING, "Could not register placeholder expansion '"
                    + entry.holder.identifier() + "'", e);
        }
        return false;
    }

    /** One pass on the next tick that registers the expansions PlaceholderAPI does not have. */
    private void queueRetry() {
        if (!retryQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            host.sync(() -> {
                retryQueued.set(false);
                retry();
            });
        } catch (RuntimeException notScheduled) {
            retryQueued.set(false);
        }
    }

    private void retry() {
        if (!available()) {
            return;
        }
        List<Registered> all;
        synchronized (expansions) {
            all = new ArrayList<>(expansions.values());
        }
        for (Registered entry : all) {
            synchronized (expansions) {
                if (expansions.get(entry.holder.key()) != entry) {
                    continue;
                }
            }
            if (!isLive(entry)) {
                tryRegister(entry);
            }
        }
    }

    private boolean isLive(Registered entry) {
        Object handle = entry.handle;
        if (handle == null) {
            return false;
        }
        try {
            return host.isRegistered(handle);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private <T> CompletableFuture<T> onMain(T original, Supplier<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable fallback = () -> future.complete(original);
        waiting.add(fallback);
        try {
            host.sync(() -> {
                waiting.remove(fallback);
                future.complete(work.get());
            });
        } catch (RuntimeException notScheduled) {
            waiting.remove(fallback);
            future.complete(original);
        }
        return future;
    }

    private void leftOffMain(Object text) {
        if (available()) {
            host.trace(() -> "PlaceholderAPI is not used off the main thread; left as written: " + text);
        }
    }

    private void markBroken(LinkageError error) {
        if (!broken) {
            broken = true;
            host.logger().log(Level.WARNING, "PlaceholderAPI could not be used; placeholders stay as written.", error);
        }
    }

    /** An expansion of this plugin and, while PlaceholderAPI has it, its handle there. */
    private static final class Registered {

        final PapiHolder holder;
        volatile Object handle;

        Registered(PapiHolder holder) {
            this.holder = holder;
        }
    }
}
