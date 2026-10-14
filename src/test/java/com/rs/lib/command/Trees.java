package com.rs.lib.command;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Builds roots for tests without a plugin: the same builders, a root that only resolves. */
final class Trees {

    private Trees() {
    }

    static SubCommandBuilder sub(String name) {
        return new SubCommandBuilder(null, name);
    }

    static RootCommand root(String permission, String fallback, Consumer<RootContext> onEmpty, SubCommandBuilder... subs) {
        List<Node> top = new ArrayList<>();
        Node fallbackNode = null;
        for (SubCommandBuilder sub : subs) {
            Node node = sub.build("");
            top.add(node);
            if (node.name.equals(fallback)) {
                fallbackNode = node;
            }
        }
        return new RootCommand(null, "rk", permission, "Main", List.of(), null, false, false, onEmpty, top, fallbackNode);
    }
}
