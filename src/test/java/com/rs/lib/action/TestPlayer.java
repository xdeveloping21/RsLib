package com.rs.lib.action;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;

/** A player without a server that records what actions did to it. */
final class TestPlayer {

    final UUID id = UUID.randomUUID();
    final List<Component> messages = new ArrayList<>();
    /** Commands run by the player, each with whether it was an operator at that moment. */
    final List<String> commands = new ArrayList<>();
    final List<String> touched = new ArrayList<>();
    boolean op;
    boolean online = true;
    boolean closed;
    Title title;
    Component actionBar;
    RuntimeException commandFailure;
    final Player player;

    TestPlayer() {
        player = (Player) Proxy.newProxyInstance(TestPlayer.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> "Steve";
                    case "isOnline" -> online;
                    case "sendMessage" -> {
                        if (args[0] instanceof Component c) {
                            messages.add(c);
                        } else {
                            messages.add(Component.text(String.valueOf(args[0])));
                        }
                        yield null;
                    }
                    case "isOp" -> op;
                    case "setOp" -> {
                        op = (Boolean) args[0];
                        yield null;
                    }
                    case "performCommand" -> {
                        commands.add(args[0] + (op ? " (op)" : ""));
                        if (commandFailure != null) {
                            throw commandFailure;
                        }
                        yield true;
                    }
                    case "closeInventory" -> {
                        closed = true;
                        yield null;
                    }
                    case "showTitle" -> {
                        title = (Title) args[0];
                        yield null;
                    }
                    case "sendActionBar" -> {
                        actionBar = (Component) args[0];
                        yield null;
                    }
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "TestPlayer";
                    default -> {
                        touched.add(method.getName());
                        throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    /** The visible text of every message, in order. */
    List<String> texts() {
        List<String> out = new ArrayList<>();
        for (Component message : messages) {
            out.add(PlainTextComponentSerializer.plainText().serialize(message));
        }
        return out;
    }
}
