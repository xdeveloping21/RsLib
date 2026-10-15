package com.rs.lib.papi;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import com.rs.lib.yml.YmlTestSupport.Captured;

/** A server and a PlaceholderAPI in memory: sync tasks wait in a queue until {@link #runTasks()}. */
final class FakePapiHost implements PapiHost {

    final Captured log = new Captured();
    final List<Runnable> tasks = new ArrayList<>();
    final List<String> traces = new ArrayList<>();
    final List<String> resolved = new ArrayList<>();
    /** What PlaceholderAPI has, by lower-case identifier (handles are the holders). */
    final Map<String, PapiHolder> papi = new LinkedHashMap<>();
    final List<String> unregistered = new ArrayList<>();

    volatile boolean primary = true;
    volatile boolean enabled = true;
    volatile boolean disabledPlugin;
    volatile boolean refuse;
    volatile Error registerError;
    int lookups;
    UnaryOperator<String> resolver = text -> text.replace("%server_name%", "Lobby");

    @Override
    public Logger logger() {
        return log.logger;
    }

    @Override
    public boolean isPrimaryThread() {
        return primary;
    }

    @Override
    public boolean papiEnabled() {
        lookups++;
        return enabled;
    }

    @Override
    public void forget() {
    }

    @Override
    public String setPlaceholders(Player viewer, String text) {
        resolved.add(text);
        return resolver.apply(text);
    }

    @Override
    public Object register(PapiHolder holder) {
        if (registerError != null) {
            throw registerError;
        }
        if (refuse) {
            return null;
        }
        papi.put(holder.key(), holder);
        return holder;
    }

    @Override
    public boolean isRegistered(Object handle) {
        return papi.get(((PapiHolder) handle).key()) == handle;
    }

    @Override
    public void unregister(Object handle) {
        PapiHolder holder = (PapiHolder) handle;
        if (papi.get(holder.key()) == holder) {
            papi.remove(holder.key());
            unregistered.add(holder.identifier());
        }
    }

    @Override
    public void sync(Runnable task) {
        if (disabledPlugin) {
            throw new IllegalStateException("plugin disabled");
        }
        tasks.add(task);
    }

    @Override
    public void trace(Supplier<String> message) {
        traces.add(message.get());
    }

    @Override
    public String defaultAuthor() {
        return "Raul";
    }

    @Override
    public String defaultVersion() {
        return "1.2.3";
    }

    int runTasks() {
        List<Runnable> now = new ArrayList<>(tasks);
        tasks.clear();
        now.forEach(Runnable::run);
        return now.size();
    }

    /** An offline player that only knows its name. */
    static OfflinePlayer player(String name) {
        return (OfflinePlayer) Proxy.newProxyInstance(OfflinePlayer.class.getClassLoader(),
                new Class<?>[] {OfflinePlayer.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName", "toString" -> name;
                    case "hashCode" -> name.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
