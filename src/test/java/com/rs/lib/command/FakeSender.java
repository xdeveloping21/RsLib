package com.rs.lib.command;

import java.lang.reflect.Proxy;
import java.util.Set;

import org.bukkit.command.CommandSender;

/** A console-like sender with a fixed set of permissions, without a server. */
final class FakeSender {

    private FakeSender() {
    }

    static CommandSender with(String... permissions) {
        Set<String> granted = Set.of(permissions);
        return (CommandSender) Proxy.newProxyInstance(FakeSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> args[0] instanceof String perm && granted.contains(perm);
                    case "getName" -> "tester";
                    case "isOp" -> false;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakeSender" + granted;
                    default -> method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                });
    }
}
