package com.rs.lib.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * An immutable set of values picked at random in proportion to their weights.
 *
 * <pre>{@code
 * WeightedRandomPool<String> pool = WeightedRandomPool.<String>builder()
 *         .add("common", 70).add("rare", 25).add("legendary", 4.5).build();
 * String reward = pool.pick();
 * }</pre>
 *
 * <p>Weights are {@code double}s used as given (never truncated). A weight that is zero,
 * negative, NaN or infinite is ignored, so a config can switch an entry off with
 * {@code weight: 0}. Picking draws a uniform number in {@code [0, total)} and finds the entry
 * whose running total is the first one above it (binary search, O(log n)). Safe to share
 * between threads.</p>
 *
 * @param <T> the type of the values
 */
public final class WeightedRandomPool<T> {

    private static final WeightedRandomPool<?> EMPTY = new WeightedRandomPool<>(List.of(), new double[0]);

    private final List<T> values;
    /** Running totals: {@code cumulative[i]} is the sum of the weights of entries 0 to i. */
    private final double[] cumulative;

    private WeightedRandomPool(List<T> values, double[] cumulative) {
        this.values = values;
        this.cumulative = cumulative;
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /** A random value, using {@link ThreadLocalRandom}. */
    public T pick() {
        return pick(ThreadLocalRandom.current());
    }

    /**
     * A random value drawn with {@code random}.
     *
     * @throws NoSuchElementException when the pool is empty
     */
    public T pick(Random random) {
        Objects.requireNonNull(random, "random");
        int size = cumulative.length;
        if (size == 0) {
            throw new NoSuchElementException("the pool is empty");
        }
        double r = random.nextDouble() * cumulative[size - 1];
        int low = 0;
        int high = size - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (cumulative[mid] > r) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return values.get(low);
    }

    /** Number of entries with a usable weight. */
    public int size() {
        return cumulative.length;
    }

    public boolean isEmpty() {
        return cumulative.length == 0;
    }

    /** Sum of every usable weight; 0 when empty. */
    public double totalWeight() {
        return cumulative.length == 0 ? 0 : cumulative[cumulative.length - 1];
    }

    /** The values in the order they were added (an entry added twice appears twice); unmodifiable. */
    public List<T> values() {
        return values;
    }

    @Override
    public String toString() {
        return "WeightedRandomPool" + values + " (total " + totalWeight() + ")";
    }

    /**
     * Collects entries for a pool. Not thread-safe; {@link #build()} can be called more than once
     * and every pool it returns is independent of later {@link #add} calls.
     *
     * @param <T> the type of the values
     */
    public static final class Builder<T> {

        private final List<T> values = new ArrayList<>();
        private double[] cumulative = new double[8];
        private double total;

        private Builder() {
        }

        /**
         * Adds {@code value} with {@code weight}; ignored when the weight is not a positive,
         * finite number.
         *
         * @throws NullPointerException     when {@code value} is null
         * @throws IllegalArgumentException when the total weight would no longer be finite
         */
        public Builder<T> add(T value, double weight) {
            Objects.requireNonNull(value, "value");
            if (!(weight > 0) || Double.isInfinite(weight)) {
                return this;
            }
            double next = total + weight;
            if (Double.isInfinite(next)) {
                throw new IllegalArgumentException("the total weight is too large: " + total + " + " + weight);
            }
            if (values.size() == cumulative.length) {
                cumulative = Arrays.copyOf(cumulative, cumulative.length * 2);
            }
            cumulative[values.size()] = next;
            values.add(value);
            total = next;
            return this;
        }

        @SuppressWarnings("unchecked")
        public WeightedRandomPool<T> build() {
            if (values.isEmpty()) {
                return (WeightedRandomPool<T>) EMPTY;
            }
            return new WeightedRandomPool<>(Collections.unmodifiableList(new ArrayList<>(values)),
                    Arrays.copyOf(cumulative, values.size()));
        }
    }
}
