package com.rs.lib.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Values are shared only inside {@code during}, only within one tick, and per source and key. */
class SharedPlaceholdersTest {

    private final long[] tick = {1};
    private final AtomicInteger computed = new AtomicInteger();
    private final Supplier<String> source = () -> "v" + computed.incrementAndGet();

    @AfterEach
    void restore() {
        SharedPlaceholders.clock(null);
    }

    @Test
    void outsideDuringEveryCallComputes() {
        SharedPlaceholders.clock(() -> tick[0]);
        assertFalse(SharedPlaceholders.active());
        assertEquals("v1", SharedPlaceholders.value(source, "k", source));
        assertEquals("v2", SharedPlaceholders.value(source, "k", source));
    }

    @Test
    void insideDuringAValueIsComputedOncePerTickAcrossCalls() {
        SharedPlaceholders.clock(() -> tick[0]);
        String[] seen = new String[4];
        SharedPlaceholders.during(() -> seen[0] = SharedPlaceholders.value(source, "k", source));
        SharedPlaceholders.during(() -> seen[1] = SharedPlaceholders.value(source, "k", source));
        assertEquals("v1", seen[0]);
        assertEquals("v1", seen[1], "a second viewer in the same tick reuses it");

        tick[0]++;
        SharedPlaceholders.during(() -> seen[2] = SharedPlaceholders.value(source, "k", source));
        assertEquals("v2", seen[2], "a new tick computes again");
        assertEquals(2, computed.get());
    }

    @Test
    void sourcesAndKeysNeverShare() {
        SharedPlaceholders.clock(() -> tick[0]);
        Supplier<String> other = () -> "other";
        SharedPlaceholders.during(() -> {
            assertEquals("v1", SharedPlaceholders.value(source, "a", source));
            assertEquals("v2", SharedPlaceholders.value(source, "b", source));
            assertEquals("other", SharedPlaceholders.value(other, "a", other));
            assertEquals("v1", SharedPlaceholders.value(source, "a", source));
        });
    }

    @Test
    void nullIsKeptAndNestedCallsShare() {
        SharedPlaceholders.clock(() -> tick[0]);
        AtomicInteger nulls = new AtomicInteger();
        Supplier<String> none = () -> {
            nulls.incrementAndGet();
            return null;
        };
        SharedPlaceholders.during(() -> {
            assertNull(SharedPlaceholders.value(none, "x", none));
            SharedPlaceholders.during(() -> assertNull(SharedPlaceholders.value(none, "x", none)));
            assertTrue(SharedPlaceholders.active());
        });
        assertFalse(SharedPlaceholders.active());
        assertEquals(1, nulls.get());
    }

    @Test
    void withoutAServerTickEachCallIsItsOwnTick() {
        SharedPlaceholders.clock(null);
        SharedPlaceholders.during(() -> {
            SharedPlaceholders.value(source, "k", source);
            SharedPlaceholders.value(source, "k", source);
        });
        SharedPlaceholders.during(() -> SharedPlaceholders.value(source, "k", source));
        assertEquals(2, computed.get(), "shared inside one call, never across calls");
    }

    @Test
    void aThrowLeavesSharingOff() {
        assertThrows(IllegalStateException.class, () -> SharedPlaceholders.during(() -> {
            throw new IllegalStateException("boom");
        }));
        assertFalse(SharedPlaceholders.active());
    }
}
