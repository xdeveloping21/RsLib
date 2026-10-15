package com.rs.lib.util;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.World;
import org.bukkit.entity.Player;

/** Interface fakes for util tests that run without a server. */
final class Fakes {

    private Fakes() {
    }

    /** A world that only knows its name; equal only to itself. */
    static World world(String name) {
        return (World) Proxy.newProxyInstance(Fakes.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "World[" + name + "]";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A player whose level and bar live in {@code state} (keys "level" and "exp"). */
    static Player experience(Map<String, Number> state) {
        return (Player) Proxy.newProxyInstance(Fakes.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLevel" -> state.getOrDefault("level", 0).intValue();
                    case "getExp" -> state.getOrDefault("exp", 0f).floatValue();
                    case "setLevel" -> {
                        state.put("level", (Integer) args[0]);
                        yield null;
                    }
                    case "setExp" -> {
                        float exp = (Float) args[0];
                        if (exp < 0 || exp > 1) {
                            throw new IllegalArgumentException("Experience progress must be between 0.0 and 1.0");
                        }
                        state.put("exp", exp);
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    static Map<String, Number> state(int level, float exp) {
        Map<String, Number> state = new HashMap<>();
        state.put("level", level);
        state.put("exp", exp);
        return state;
    }
}
