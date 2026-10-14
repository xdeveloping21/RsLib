package com.rs.lib.reload;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

import com.rs.lib.Rs;

/**
 * Reloads a plugin without restarting it: YAML files, messages, commands, then every
 * {@link Reloadable} in the order it was registered. One failing step is logged and the
 * rest still run. Classes are never reloaded; a new jar needs a restart.
 */
public final class ReloadManager {

    private final Rs rs;
    private final List<Reloadable> reloadables = new CopyOnWriteArrayList<>();
    private boolean running;

    public ReloadManager(Rs rs) {
        this.rs = Objects.requireNonNull(rs, "rs");
    }

    public void register(Reloadable reloadable) {
        reloadables.add(Objects.requireNonNull(reloadable, "reloadable"));
    }

    /**
     * Runs on the main thread; file reads are synchronous on purpose (it is an admin command).
     * A reload asked for while one is running (a {@code Reloadable} calling it) is ignored.
     */
    public void reloadPlugin() {
        if (running) {
            rs.plugin().getLogger().warning("A reload was asked for during a reload; ignored.");
            return;
        }
        running = true;
        try {
            reloadSteps();
        } finally {
            running = false;
        }
    }

    private void reloadSteps() {
        if (rs.ymlOrNull() != null) {
            step("files", () -> rs.ymlOrNull().reloadAll());
        }
        if (rs.langOrNull() != null) {
            step("messages", () -> rs.langOrNull().reload());
        }
        step("commands", () -> {
            rs.commands().reRegisterAll();
            rs.commands().applyLang();
        });
        for (Reloadable reloadable : reloadables) {
            step(reloadable.getClass().getName(), reloadable::reload);
        }
    }

    private void step(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError e) {
            rs.plugin().getLogger().log(Level.SEVERE, "Reload step failed (" + what + "); the other steps still ran.", e);
        }
    }
}
