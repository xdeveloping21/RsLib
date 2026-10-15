package com.rs.lib.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An immutable copy of a list cut into pages of a fixed size. Pages start at 1 and any page
 * number is brought into range; an empty list has one empty page.
 *
 * @param <T> the element type
 */
public final class Pagination<T> {

    private final List<T> items;
    private final int pageSize;

    private Pagination(List<T> items, int pageSize) {
        this.items = items;
        this.pageSize = pageSize;
    }

    /**
     * Copies {@code items} (null reads as empty) into pages of {@code pageSize}.
     *
     * @throws IllegalArgumentException when {@code pageSize} is less than 1
     */
    public static <T> Pagination<T> of(List<T> items, int pageSize) {
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be at least 1, got " + pageSize);
        }
        List<T> copy = items == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(items));
        return new Pagination<>(copy, pageSize);
    }

    public int pageSize() {
        return pageSize;
    }

    /** How many elements there are. */
    public int size() {
        return items.size();
    }

    /** How many pages there are; at least 1. */
    public int totalPages() {
        return Math.max(1, (items.size() + pageSize - 1) / pageSize);
    }

    /** The elements of {@code page} (brought into range), possibly fewer than a page. */
    public List<T> page(int page) {
        int p = clamp(page);
        int from = (p - 1) * pageSize;
        return items.subList(Math.min(from, items.size()), Math.min(from + pageSize, items.size()));
    }

    /** {@code page} brought into {@code 1..totalPages()}. */
    int clamp(int page) {
        return Math.max(1, Math.min(page, totalPages()));
    }

    /** The element at {@code index} of the whole list, or null past its end. */
    T at(int index) {
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    /** True when {@code index} is inside the list. */
    boolean has(int index) {
        return index >= 0 && index < items.size();
    }
}
