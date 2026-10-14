package com.rs.lib.command;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.bukkit.command.CommandSender;

import net.kyori.adventure.text.Component;

/** A list split in pages numbered from 1; an empty list still has one (empty) page. */
public final class Page<T> {

    private final List<T> items;
    private final int pageSize;

    private Page(List<T> items, int pageSize) {
        this.items = items;
        this.pageSize = pageSize;
    }

    public static <T> Page<T> of(List<T> items, int pageSize) {
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be at least 1: " + pageSize);
        }
        return new Page<>(List.copyOf(Objects.requireNonNull(items, "items")), pageSize);
    }

    public int size() {
        return items.size();
    }

    public int pageSize() {
        return pageSize;
    }

    public int totalPages() {
        return Math.max(1, (items.size() + pageSize - 1) / pageSize);
    }

    /** {@code page} moved into {@code [1, totalPages()]}. */
    public int clamp(int page) {
        return Math.max(1, Math.min(page, totalPages()));
    }

    /** The items of {@code page} (clamped). */
    public List<T> page(int page) {
        int from = (clamp(page) - 1) * pageSize;
        return items.subList(from, Math.min(items.size(), from + pageSize));
    }

    /** Sends each item of {@code page} as one line. */
    public void send(CommandSender sender, int page, Function<T, Component> renderer) {
        for (T item : page(page)) {
            sender.sendMessage(renderer.apply(item));
        }
    }
}
