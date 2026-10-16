package com.rs.lib;

import com.rs.lib.update.internal.SiteClient;

/**
 * Which modules a plugin uses, declared once in {@link RsPlugin#buildSpec()}.
 *
 * <p>A module that is not declared is not created, and its accessor on {@link Rs} throws an
 * {@link UnsupportedOperationException} that names the builder call that is missing.</p>
 */
public final class RsSpec {

    private final String config;
    private final boolean lang;
    private final boolean debugCommand;
    private final boolean commandPriority;
    private final boolean db;
    private final boolean updates;
    private final String updatesProduct;
    private final String items;
    private final boolean guis;
    private final boolean teleports;
    private final boolean rewards;

    private RsSpec(Builder b) {
        this.config = b.config;
        this.lang = b.lang;
        this.debugCommand = b.debugCommand;
        this.commandPriority = b.commandPriority;
        this.db = b.db;
        this.updates = b.updates;
        this.updatesProduct = b.updatesProduct;
        this.items = b.items;
        this.guis = b.guis;
        this.teleports = b.teleports;
        this.rewards = b.rewards;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** An empty spec: no config, no messages. */
    public static RsSpec empty() {
        return new Builder().build();
    }

    /** Main config file name, or null when the plugin has none. */
    public String config() {
        return config;
    }

    public boolean lang() {
        return lang;
    }

    public boolean debugCommand() {
        return debugCommand;
    }

    public boolean commandPriority() {
        return commandPriority;
    }

    /** Whether the plugin uses a database ({@code rs().db()}). */
    public boolean db() {
        return db;
    }

    /** True when the plugin watches rsdevelopment.dev for new versions. */
    public boolean updates() {
        return updates;
    }

    /**
     * The product id given to {@link Builder#updates(String)}, or null: the plugin's name in
     * lower case (or update checks are off).
     */
    public String updatesProduct() {
        return updatesProduct;
    }

    /** The items file registered on start ({@code rs().items().loadAll}), or null. */
    public String items() {
        return items;
    }

    /** True when the plugin has menus ({@code rs().guis()}, loaded from {@code guis/}). */
    public boolean guis() {
        return guis;
    }

    /** True when the plugin uses teleports with a wait ({@code rs().teleports()}). */
    public boolean teleports() {
        return teleports;
    }

    /** True when the plugin keeps rewards for later in its database ({@code rs().rewards()}). */
    public boolean rewards() {
        return rewards;
    }

    public static final class Builder {

        private String config;
        private boolean lang;
        private boolean debugCommand;
        private boolean commandPriority;
        private boolean db;
        private boolean updates;
        private String updatesProduct;
        private String items;
        private boolean guis;
        private boolean teleports;
        private boolean rewards;

        private Builder() {
        }

        /** Main config file, seeded from the jar and merged with new keys on every start. */
        public Builder config(String fileName) {
            if (fileName == null || fileName.isBlank()) {
                throw new IllegalArgumentException("config file name is blank");
            }
            this.config = fileName.trim();
            return this;
        }

        /** Messages in {@code lang/messages_<code>.yml}; the code is the {@code lang} key of the config. */
        public Builder lang() {
            this.lang = true;
            return this;
        }

        /** Adds a {@code debug} subcommand to every root command ({@code <plugin>.admin.debug}). */
        public Builder debugCommand() {
            this.debugCommand = true;
            return this;
        }

        /**
         * The plugin's root commands take their names (and the aliases RsLib manages) from other
         * plugins that registered them first, such as EssentialsX.
         */
        public Builder commandPriority() {
            this.commandPriority = true;
            return this;
        }

        /**
         * A SQLite or MySQL database ({@code rs().db()}), configured by the {@code database:}
         * section of the main config; without that section, SQLite in {@code database.db}.
         */
        public Builder db() {
            this.db = true;
            return this;
        }

        /**
         * Watches rsdevelopment.dev for a newer version of the product whose id is the plugin's
         * name in lower case, and tells the owner when there is one (see
         * {@link com.rs.lib.update.UpdateChecker}). Declare {@code <plugin>.admin.update}
         * (default op) in plugin.yml.
         */
        public Builder updates() {
            this.updates = true;
            this.updatesProduct = null;
            return this;
        }

        /**
         * Same as {@link #updates()} for a product id that is not the plugin's name: 1 to 40
         * characters among {@code a-z}, {@code 0-9} and {@code -} (capitals are lowered).
         */
        public Builder updates(String productId) {
            String id = SiteClient.normalizeId(productId);
            if (id == null) {
                throw new IllegalArgumentException("'" + productId + "' is not a product id"
                        + " (1 to 40 characters among a-z, 0-9 and -)");
            }
            this.updates = true;
            this.updatesProduct = id;
            return this;
        }

        /**
         * Registers every top-level section of {@code fileName} (seeded from the jar and merged
         * like the config) as an item of {@code rs().items()} on start and on reload. Needs
         * {@link #config(String)}.
         */
        public Builder items(String fileName) {
            if (fileName == null || fileName.isBlank()) {
                throw new IllegalArgumentException("items file name is blank");
            }
            this.items = fileName.trim();
            return this;
        }

        /**
         * Menus: every {@code guis/*.yml} (seeded from the jar and merged like the config) is
         * loaded on start and on reload, reached with {@code rs().guis()}. Needs
         * {@link #config(String)}.
         */
        public Builder guis() {
            this.guis = true;
            return this;
        }

        /** Teleports with a wait, cancelled by moving or damage ({@code rs().teleports()}). */
        public Builder teleports() {
            this.teleports = true;
            return this;
        }

        /**
         * Rewards kept for later ({@code rs().rewards()}): what does not fit with
         * {@code OverflowPolicy.QUEUE} (the default) and rewards owed to players who are not online wait in
         * the table {@code rslib_pending_rewards} and are handed over on join. Needs
         * {@link #db()}. Giving rewards to online players works without it: what does not fit
         * is then dropped at their feet, with one warning in the console.
         */
        public Builder rewards() {
            this.rewards = true;
            return this;
        }

        public RsSpec build() {
            return new RsSpec(this);
        }
    }
}
