package com.rs.lib.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import com.rs.lib.RsLibPlugin;
import com.rs.lib.scheduler.RsScheduler;
import com.rs.lib.scheduler.TaskHandle;

/**
 * The server's directory of player names and UUIDs, answered from memory on any thread.
 *
 * <p>Plugins use it instead of {@code Bukkit.getOfflinePlayer(...)}, which may read the disk or
 * ask Mojang on the main thread. It is one directory for the whole server, owned by RsLib (not
 * by each plugin) and kept in {@code plugins/RsLib/player-names.tsv}, one line per player:
 * {@code uuid<TAB>name<TAB>lastSeenMillis}. It learns from every allowed login and every join
 * (the newest sighting wins a name), from the players online when RsLib starts and from the
 * file. On the very first start, when that file does not exist yet, it imports the server's
 * {@code usercache.json} once. The file is loaded off the main thread, saved off the
 * main thread every five minutes when something changed, and saved before RsLib stops. Past
 * {@value #MAX_ENTRIES} players, the ones seen longest ago are dropped.</p>
 *
 * <p>Name lookups ignore case. Until {@link #isLoaded()} is true only the players seen since
 * start are known.</p>
 */
public final class PlayerNames {

    /** Most players kept; beyond it the ones seen longest ago are dropped. */
    public static final int MAX_ENTRIES = 100_000;

    static final String FILE_NAME = "player-names.tsv";
    private static final long SAVE_PERIOD_TICKS = 5 * 60 * 20L;
    private static final long LOAD_WAIT_SECONDS = 10L;

    private static final NameDirectory DIRECTORY = new NameDirectory(MAX_ENTRIES);
    private static final Listener LISTENER = new JoinListener();
    private static final Object SAVE_LOCK = new Object();

    private static volatile Running running;
    private static volatile boolean loaded;

    private PlayerNames() {
    }

    /** The last name seen for {@code id}. */
    public static Optional<String> name(UUID id) {
        return DIRECTORY.name(id);
    }

    /** The last name seen for {@code id}, or {@code fallback}. */
    public static String nameOr(UUID id, String fallback) {
        return DIRECTORY.name(id).orElse(fallback);
    }

    /**
     * The UUID of the player last seen with {@code name}, in any case. On the main thread, a name
     * the directory does not know is also looked up in the server's in-memory profile cache.
     */
    public static Optional<UUID> uuid(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        Optional<UUID> known = DIRECTORY.uuid(name);
        if (known.isPresent() || Bukkit.getServer() == null || !Bukkit.isPrimaryThread()) {
            return known;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null ? Optional.empty() : Optional.of(cached.getUniqueId());
    }

    /**
     * Like {@link #uuid(String)}, asking Mojang ({@link PlayerLookup}) for a name the directory
     * does not know. Known names complete at once; Mojang's answers complete off the main thread.
     */
    public static CompletableFuture<Optional<UUID>> uuidAsync(String name) {
        Optional<UUID> known = DIRECTORY.uuid(name);
        return known.isPresent() ? CompletableFuture.completedFuture(known) : PlayerLookup.fetchUuid(name);
    }

    /** How many players the directory knows. */
    public static int size() {
        return DIRECTORY.size();
    }

    /** True once the saved directory has been read. */
    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * Records that {@code id} was called {@code name} at {@code seenAt} (epoch millis). An older
     * sighting than the one known, or a name that is empty, longer than 64 characters or has
     * control characters, is ignored.
     */
    public static void remember(UUID id, String name, long seenAt) {
        DIRECTORY.remember(id, name, seenAt);
    }

    /** The listener that records logins and joins; {@link #start} registers it. */
    public static Listener joinListener() {
        return LISTENER;
    }

    /**
     * Starts the directory for RsLib: registers the listener, records the players online and
     * loads the file off the main thread. Called by RsLib when it enables; not for plugins.
     */
    public static synchronized void start(RsLibPlugin rslib) {
        if (running != null) {
            shutdown();
        }
        Logger log = rslib.getLogger();
        Path file = rslib.getDataFolder().toPath().resolve(FILE_NAME);
        Path usercache = Bukkit.getWorldContainer().toPath().resolve("usercache.json");
        RsScheduler scheduler = new RsScheduler(rslib);
        loaded = false;

        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            DIRECTORY.remember(player.getUniqueId(), player.getName(), now);
        }
        rslib.getServer().getPluginManager().registerEvents(LISTENER, rslib);

        CompletableFuture<Void> load = scheduler.supplyAsync(() -> {
            loadOrImport(DIRECTORY, file, usercache, log);
            return null;
        });
        TaskHandle saver = scheduler.timerAsync(SAVE_PERIOD_TICKS, SAVE_PERIOD_TICKS, () -> {
            if (loaded) {
                saveIfDirty(file, log);
            }
        });
        Running started = new Running(file, log, load, saver);
        running = started;
        load.whenComplete((ignored, error) -> {
            if (error != null) {
                log.log(Level.WARNING, "Could not load " + FILE_NAME + "; it is left untouched and only"
                        + " players seen from now on are known", error);
            } else if (running == started) {
                loaded = true;
            }
        });
    }

    /**
     * Stops the periodic save and saves now, on the calling thread, waiting up to ten seconds
     * for a load still running. Called by RsLib when it disables; not for plugins.
     */
    public static synchronized void shutdown() {
        Running current = running;
        if (current == null) {
            return;
        }
        running = null;
        loaded = false;
        current.saver().cancel();
        try {
            current.load().get(LOAD_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException slow) {
            current.log().warning("Player names were still loading at shutdown; "
                    + FILE_NAME + " was not saved, to keep its contents.");
            return;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException failed) {
            // Already reported; the file was not read, so it must not be overwritten.
            return;
        }
        // The load finished: the directory holds the file's contents, so saving loses nothing.
        saveIfDirty(current.file(), current.log());
    }

    /**
     * Reads {@code file}; when it does not exist (the first start), imports the usercache
     * instead and writes {@code file} at once, even empty, so the import runs only once.
     */
    static void loadOrImport(NameDirectory directory, Path file, Path usercache, Logger log) {
        try {
            if (Files.exists(file)) {
                NameDirectory.LoadResult result = directory.load(file);
                if (result.skipped() > 0) {
                    log.warning(FILE_NAME + ": skipped " + result.skipped() + " unreadable line(s).");
                }
                return;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (Files.exists(usercache)) {
            try {
                int added = directory.importUsercache(usercache, System.currentTimeMillis());
                if (added > 0) {
                    log.info("Imported " + added + " player name(s) from usercache.json.");
                }
            } catch (IOException | RuntimeException e) {
                log.log(Level.WARNING, "Could not import usercache.json", e);
            }
        }
        directory.markDirty();
        synchronized (SAVE_LOCK) {
            try {
                directory.save(file);
            } catch (IOException e) {
                log.log(Level.WARNING, "Could not save " + FILE_NAME, e);
            }
        }
    }

    /** Saves when something changed; only once the file was read, or it would lose its contents. */
    private static void saveIfDirty(Path file, Logger log) {
        synchronized (SAVE_LOCK) {
            if (!DIRECTORY.isDirty()) {
                return;
            }
            try {
                DIRECTORY.prune();
                DIRECTORY.save(file);
            } catch (IOException | RuntimeException e) {
                log.log(Level.WARNING, "Could not save " + FILE_NAME, e);
            }
        }
    }

    /** What a started directory needs to stop. */
    private record Running(Path file, Logger log, CompletableFuture<Void> load, TaskHandle saver) {
    }

    /** Records allowed logins (off the main thread) and joins. */
    private static final class JoinListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onPreLogin(AsyncPlayerPreLoginEvent event) {
            if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                DIRECTORY.remember(event.getUniqueId(), event.getName(), System.currentTimeMillis());
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(PlayerJoinEvent event) {
            Player player = event.getPlayer();
            DIRECTORY.remember(player.getUniqueId(), player.getName(), System.currentTimeMillis());
        }
    }
}
