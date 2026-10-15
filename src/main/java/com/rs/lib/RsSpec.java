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
    private final String updatesRepo;
    private final String updatesTagPrefix;

    private RsSpec(Builder b) {
        this.config = b.config;
        this.lang = b.lang;
        this.debugCommand = b.debugCommand;
        this.commandPriority = b.commandPriority;
        this.updatesRepo = b.updatesRepo;
        this.updatesTagPrefix = b.updatesTagPrefix;
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

    /** GitHub {@code owner/repo} watched for new releases, or null when update checks are off. */
    public String updatesRepo() {
        return updatesRepo;
    }

    /** Tag prefix of the watched releases, or null for the repository's latest release. */
    public String updatesTagPrefix() {
        return updatesTagPrefix;
    }

    public static final class Builder {

        private String config;
        private boolean lang;
        private boolean debugCommand;
        private boolean commandPriority;
        private String updatesRepo;
        private String updatesTagPrefix;

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
         * Watches the latest GitHub release of {@code ownerRepo} and tells the owner when it is
         * newer than the installed version (see {@link com.rs.lib.update.UpdateChecker}). Declare
         * {@code <plugin>.admin.update} (default op) in plugin.yml.
         */
        public Builder updates(String ownerRepo) {
            return updates(ownerRepo, null);
        }

        /**
         * Same as {@link #updates(String)} for a repository shared by several plugins: only tags
         * starting with {@code tagPrefix} (such as {@code rskills-}) count.
         */
        public Builder updates(String ownerRepo, String tagPrefix) {
            if (ownerRepo == null || ownerRepo.isBlank()) {
                throw new IllegalArgumentException("update repository is blank");
            }
            this.updatesRepo = ownerRepo.trim();
            this.updatesTagPrefix = tagPrefix == null || tagPrefix.isBlank() ? null : tagPrefix.trim();
            return this;
        }

        public RsSpec build() {
            return new RsSpec(this);
        }
    }
}
