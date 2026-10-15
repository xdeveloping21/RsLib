package com.rs.lib.event.internal;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Records the events a listener fires; {@link #reaction} plays the plugins listening to them. */
final class FakeSink implements EventSink {

    final List<Event> fired = new ArrayList<>();
    boolean listened = true;
    int asked;
    Consumer<Event> reaction = event -> { };

    @Override
    public boolean listened(HandlerList handlers) {
        asked++;
        return listened;
    }

    @Override
    public void call(Event event) {
        fired.add(event);
        reaction.accept(event);
    }

    /** A world that only knows its name; equal only to itself. */
    static World world(String name) {
        return (World) Proxy.newProxyInstance(FakeSink.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
