package com.rs.lib.modifier;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * LuckPerms' recalculation events, reached only through reflection: a player's data being
 * recalculated drops that player's cached modifiers, a group's drops everyone's. Created by a
 * soft dependency only after LuckPerms was found enabled with its API; anything missing throws
 * and the watch stays off (the cache then only expires by time).
 */
final class LuckPermsWatch {

    static final String PROVIDER = "net.luckperms.api.LuckPermsProvider";
    private static final String USER_EVENT = "net.luckperms.api.event.user.UserDataRecalculateEvent";
    private static final String GROUP_EVENT = "net.luckperms.api.event.group.GroupDataRecalculateEvent";

    private final List<AutoCloseable> subscriptions;

    private LuckPermsWatch(List<AutoCloseable> subscriptions) {
        this.subscriptions = subscriptions;
    }

    static LuckPermsWatch start(JavaPlugin owner, Consumer<UUID> onPlayer, Runnable onEveryone) {
        Plugin luckPerms = Bukkit.getPluginManager().getPlugin("LuckPerms");
        if (luckPerms == null) {
            throw new IllegalStateException("LuckPerms is not installed");
        }
        ClassLoader loader = luckPerms.getClass().getClassLoader();
        try {
            Object api = Class.forName(PROVIDER, true, loader).getMethod("get").invoke(null);
            Object bus = Class.forName("net.luckperms.api.LuckPerms", true, loader).getMethod("getEventBus").invoke(api);
            Method subscribe = Class.forName("net.luckperms.api.event.EventBus", true, loader)
                    .getMethod("subscribe", Object.class, Class.class, Consumer.class);
            Class<?> userEvent = Class.forName(USER_EVENT, true, loader);
            Method getUser = userEvent.getMethod("getUser");
            Method getUniqueId = getUser.getReturnType().getMethod("getUniqueId");
            Class<?> groupEvent = Class.forName(GROUP_EVENT, true, loader);

            List<AutoCloseable> subscriptions = new ArrayList<>();
            Consumer<Object> userHandler = event -> {
                try {
                    Object user = getUser.invoke(event);
                    Object id = user == null ? null : getUniqueId.invoke(user);
                    if (id instanceof UUID uuid) {
                        onPlayer.accept(uuid);
                    } else {
                        onEveryone.run();
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    onEveryone.run();
                }
            };
            Consumer<Object> groupHandler = event -> onEveryone.run();
            subscriptions.add(closeable(subscribe.invoke(bus, owner, userEvent, userHandler)));
            subscriptions.add(closeable(subscribe.invoke(bus, owner, groupEvent, groupHandler)));
            return new LuckPermsWatch(subscriptions);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("LuckPerms refused the event subscription", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("LuckPerms' API is not the expected one", e);
        }
    }

    private static AutoCloseable closeable(Object subscription) {
        return subscription instanceof AutoCloseable closeable ? closeable : () -> { };
    }

    /** Ends both subscriptions (LuckPerms also ends them when the owner is disabled). */
    void close() {
        for (AutoCloseable subscription : subscriptions) {
            try {
                subscription.close();
            } catch (Exception ignored) {
                // LuckPerms already dropped it.
            }
        }
    }
}
