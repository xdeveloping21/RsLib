package com.rs.lib.audit;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What to read from the audit log: every filter is optional and they all apply together.
 *
 * <pre>
 * AuditQuery query = AuditQuery.builder().player(id).actions("market-buy").since(Duration.ofDays(7)).page(2).build();
 * </pre>
 */
public final class AuditQuery {

    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 100;

    private final String plugin;
    private final Set<String> actions;
    private final UUID player;
    private final UUID actor;
    private final UUID target;
    private final Long from;
    private final Long to;
    private final int page;
    private final int pageSize;

    private AuditQuery(Builder b) {
        this.plugin = b.plugin;
        this.actions = Set.copyOf(b.actions);
        this.player = b.player;
        this.actor = b.actor;
        this.target = b.target;
        this.from = b.from;
        this.to = b.to;
        this.page = b.page;
        this.pageSize = b.pageSize;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Only this plugin's entries (any case), or null for every plugin. */
    public String plugin() {
        return plugin;
    }

    /** Only these actions; empty for every action. */
    public Set<String> actions() {
        return actions;
    }

    /** Only entries this player did or that were done to them, or null. */
    public UUID player() {
        return player;
    }

    /** Only entries this player did, or null. */
    public UUID actor() {
        return actor;
    }

    /** Only entries done to this player, or null. */
    public UUID target() {
        return target;
    }

    /** Only entries recorded at or after this time (epoch ms), or null. */
    public Long from() {
        return from;
    }

    /** Only entries recorded before this time (epoch ms), or null. */
    public Long to() {
        return to;
    }

    /** The page, from 1. */
    public int page() {
        return page;
    }

    public int pageSize() {
        return pageSize;
    }

    /** Whether {@code pluginName} passes the plugin filter. */
    boolean acceptsPlugin(String pluginName) {
        return plugin == null || plugin.equalsIgnoreCase(pluginName);
    }

    public static final class Builder {

        private String plugin;
        private final Set<String> actions = new LinkedHashSet<>();
        private UUID player;
        private UUID actor;
        private UUID target;
        private Long from;
        private Long to;
        private int page = 1;
        private int pageSize = DEFAULT_PAGE_SIZE;

        private Builder() {
        }

        /** Only the entries of the plugin called {@code name}; null for every plugin. */
        public Builder plugin(String name) {
            this.plugin = name == null || name.isBlank() ? null : name.trim();
            return this;
        }

        /** Only these actions (written as when recorded: case, spaces and underscores do not matter). */
        public Builder actions(String... names) {
            for (String name : names) {
                actions.add(AuditLog.normalizeAction(name));
            }
            return this;
        }

        /** Only what {@code id} did or what was done to them. */
        public Builder player(UUID id) {
            this.player = id;
            return this;
        }

        /** Only what {@code id} did. */
        public Builder actor(UUID id) {
            this.actor = id;
            return this;
        }

        /** Only what was done to {@code id}. */
        public Builder target(UUID id) {
            this.target = id;
            return this;
        }

        /** Only entries recorded at or after {@code start}. */
        public Builder from(Instant start) {
            this.from = start == null ? null : start.toEpochMilli();
            return this;
        }

        /** Only entries recorded before {@code end}. */
        public Builder to(Instant end) {
            this.to = end == null ? null : end.toEpochMilli();
            return this;
        }

        /** Only entries recorded in the last {@code window}. */
        public Builder since(Duration window) {
            Objects.requireNonNull(window, "window");
            this.from = System.currentTimeMillis() - window.toMillis();
            return this;
        }

        /** The page to read, from 1. */
        public Builder page(int number) {
            if (number < 1) {
                throw new IllegalArgumentException("Pages start at 1: " + number);
            }
            this.page = number;
            return this;
        }

        /** Entries per page, 1 to 100 (10 by default). */
        public Builder pageSize(int size) {
            if (size < 1 || size > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("A page holds 1 to " + MAX_PAGE_SIZE + " entries: " + size);
            }
            this.pageSize = size;
            return this;
        }

        public AuditQuery build() {
            return new AuditQuery(this);
        }
    }
}
