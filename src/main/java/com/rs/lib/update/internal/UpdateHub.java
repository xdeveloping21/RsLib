package com.rs.lib.update.internal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * What the update checkers of every plugin share: one HTTP client, the release cache and the
 * notices shown to players when they join (delivered by RsLib's shared join listener).
 */
public final class UpdateHub {

    private static final ReleaseFeed FEED = new ReleaseFeed(System::nanoTime);
    private static final Map<Object, Consumer<Player>> NOTICES = new ConcurrentHashMap<>();
    private static JdkTransport transport;

    /** Hands each request to the shared client, which is only created when the first one is sent. */
    private static final HttpTransport SHARED = new HttpTransport() {
        @Override
        public Response get(String url, Map<String, String> headers) throws IOException, InterruptedException {
            return client().get(url, headers);
        }

        @Override
        public long download(String url, Map<String, String> headers, Path target, long maxBytes)
                throws IOException, InterruptedException {
            return client().download(url, headers, target, maxBytes);
        }
    };

    private UpdateHub() {
    }

    public static ReleaseFeed feed() {
        return FEED;
    }

    /** The shared HTTP client of the update checkers; nothing is opened before the first request. */
    public static HttpTransport transport() {
        return SHARED;
    }

    private static synchronized JdkTransport client() {
        if (transport == null) {
            transport = new JdkTransport();
        }
        return transport;
    }

    /** Sets what {@code owner} tells a joining player (it checks permissions itself); replaces the old one. */
    public static void putNotice(Object owner, Consumer<Player> notice) {
        NOTICES.put(owner, notice);
    }

    public static void removeNotice(Object owner) {
        NOTICES.remove(owner);
    }

    public static boolean hasNotices() {
        return !NOTICES.isEmpty();
    }

    /** Gives {@code player} every pending notice; one failing notice does not stop the rest. */
    public static void deliver(JavaPlugin rslib, Player player) {
        for (Consumer<Player> notice : NOTICES.values()) {
            try {
                notice.accept(player);
            } catch (RuntimeException e) {
                rslib.getLogger().log(Level.WARNING, "An update notice failed for " + player.getName(), e);
            }
        }
    }

    /** When RsLib is disabled: closes the client and forgets the cache and the notices. */
    public static void shutdown() {
        synchronized (UpdateHub.class) {
            if (transport != null) {
                transport.close();
                transport = null;
            }
        }
        FEED.clear();
        NOTICES.clear();
    }
}
