package com.rs.lib.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;

import org.junit.jupiter.api.Test;

class WeightedRandomPoolTest {

    /** A Random that returns the given doubles in turn. */
    private static Random fixed(double... values) {
        return new Random() {
            private static final long serialVersionUID = 1L;
            private int next;

            @Override
            public double nextDouble() {
                return values[next++ % values.length];
            }
        };
    }

    @Test
    void picksByRunningTotal() {
        WeightedRandomPool<String> pool = WeightedRandomPool.<String>builder()
                .add("a", 1).add("b", 2.5).add("c", 0.5).build();
        assertEquals(4.0, pool.totalWeight());
        assertEquals("a", pool.pick(fixed(0.0)));
        assertEquals("a", pool.pick(fixed(0.2499)));
        assertEquals("b", pool.pick(fixed(0.25)));
        assertEquals("b", pool.pick(fixed(0.874)));
        assertEquals("c", pool.pick(fixed(0.875)));
        assertEquals("c", pool.pick(fixed(Math.nextDown(1.0))));
    }

    @Test
    void weightsAreNotTruncated() {
        WeightedRandomPool<String> pool = WeightedRandomPool.<String>builder()
                .add("half", 0.5).add("other half", 0.5).build();
        assertEquals(2, pool.size());
        assertEquals(1.0, pool.totalWeight());
        assertEquals("half", pool.pick(fixed(0.49)));
        assertEquals("other half", pool.pick(fixed(0.51)));
    }

    @Test
    void unusableWeightsAreIgnored() {
        WeightedRandomPool<String> pool = WeightedRandomPool.<String>builder()
                .add("zero", 0).add("negative", -1).add("nan", Double.NaN)
                .add("inf", Double.POSITIVE_INFINITY).add("ok", 3).build();
        assertEquals(List.of("ok"), pool.values());
        assertEquals(1, pool.size());
        assertEquals("ok", pool.pick());
    }

    @Test
    void emptyPoolThrowsOnPick() {
        WeightedRandomPool<String> empty = WeightedRandomPool.<String>builder().add("x", 0).build();
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.totalWeight());
        assertThrows(NoSuchElementException.class, empty::pick);
    }

    @Test
    void poolIsImmutable() {
        WeightedRandomPool.Builder<String> builder = WeightedRandomPool.<String>builder().add("a", 1);
        WeightedRandomPool<String> first = builder.build();
        builder.add("b", 1);
        assertEquals(List.of("a"), first.values());
        assertEquals(List.of("a", "b"), builder.build().values());
        assertThrows(UnsupportedOperationException.class, () -> first.values().add("c"));
        assertThrows(NullPointerException.class, () -> builder.add(null, 1));
    }

    @Test
    void frequenciesFollowWeights() {
        WeightedRandomPool<String> pool = WeightedRandomPool.<String>builder()
                .add("common", 70).add("rare", 25).add("legendary", 5).build();
        Random random = new Random(42);
        Map<String, Integer> counts = new HashMap<>();
        int draws = 200_000;
        for (int i = 0; i < draws; i++) {
            counts.merge(pool.pick(random), 1, Integer::sum);
        }
        assertEquals(0.70, counts.get("common") / (double) draws, 0.01);
        assertEquals(0.25, counts.get("rare") / (double) draws, 0.01);
        assertEquals(0.05, counts.get("legendary") / (double) draws, 0.01);
        Object value = new Object();
        assertSame(value, WeightedRandomPool.builder().add(value, 1).build().pick());
    }
}
