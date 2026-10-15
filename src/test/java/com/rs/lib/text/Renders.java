package com.rs.lib.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.Supplier;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

/**
 * Test helper: compares two renders the way a client receives them. A gradient or rainbow is a
 * virtual component, equal only to itself, so two separate renders of the same line are compared
 * by their JSON and their full structure instead.
 */
public final class Renders {

    private static final GsonComponentSerializer JSON = GsonComponentSerializer.gson();

    private Renders() {
    }

    public static void assertSameRender(Component expected, Component actual, Supplier<String> message) {
        if (expected.equals(actual)) {
            return;
        }
        assertEquals(JSON.serialize(expected), JSON.serialize(actual), message);
        assertEquals(expected.toString(), actual.toString(), message);
    }

    public static void assertSameRender(Component expected, Component actual, String message) {
        assertSameRender(expected, actual, () -> message);
    }

    public static void assertSameRender(Component expected, Component actual) {
        assertSameRender(expected, actual, () -> null);
    }
}
