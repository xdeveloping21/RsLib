package com.rs.lib;

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

    private RsSpec(Builder b) {
        this.config = b.config;
        this.lang = b.lang;
        this.debugCommand = b.debugCommand;
        this.commandPriority = b.commandPriority;
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

    public static final class Builder {

        private String config;
        private boolean lang;
        private boolean debugCommand;
        private boolean commandPriority;

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

        public RsSpec build() {
            return new RsSpec(this);
        }
    }
}
