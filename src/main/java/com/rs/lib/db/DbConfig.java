package com.rs.lib.db;

import java.io.File;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Connection settings of one plugin's database, read from the {@code database:} section of its
 * config.
 *
 * <pre>
 * database:
 *   type: sqlite          # sqlite | mysql (anything else: a warning and sqlite)
 *   file: database.db     # SQLite; relative to the plugin folder, or absolute
 *   host: localhost
 *   port: 3306
 *   database: myplugin    # default: the plugin name in lower case
 *   username: root
 *   password: ""
 *   pool-size: 4          # MySQL only; at least 1
 *   ssl: false
 *   connect-timeout-seconds: 10   # 1..3600
 *   socket-timeout-seconds: 30    # 0..3600, 0 = no limit (MySQL only)
 * </pre>
 *
 * <p>Without the section the plugin gets SQLite in {@code plugins/<Plugin>/database.db}. Every
 * key is optional; the file name and the default MySQL database name must not change, or
 * servers would lose sight of the data they already have.</p>
 */
public final class DbConfig {

    /** The two supported backends. */
    public enum Type {
        SQLITE, MYSQL
    }

    static final String DEFAULT_FILE = "database.db";
    static final int DEFAULT_PORT = 3306;
    static final int DEFAULT_POOL_SIZE = 4;
    static final int DEFAULT_CONNECT_TIMEOUT = 10;
    static final int DEFAULT_SOCKET_TIMEOUT = 30;
    static final int MAX_TIMEOUT = 3600;

    private final Type type;
    private final File sqliteFile;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final int poolSize;
    private final boolean ssl;
    private final int connectTimeoutSeconds;
    private final int socketTimeoutSeconds;

    private DbConfig(Type type, File sqliteFile, String host, int port, String database, String username,
                     String password, int poolSize, boolean ssl, int connectTimeoutSeconds, int socketTimeoutSeconds) {
        this.type = type;
        this.sqliteFile = sqliteFile;
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.poolSize = poolSize;
        this.ssl = ssl;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.socketTimeoutSeconds = socketTimeoutSeconds;
    }

    /**
     * Reads {@code section} (the {@code database:} section, or null for the defaults). Relative
     * SQLite paths resolve against the plugin's data folder.
     */
    public static DbConfig load(JavaPlugin plugin, ConfigurationSection section) {
        Objects.requireNonNull(plugin, "plugin");
        return load(plugin.getName(), plugin.getDataFolder(), plugin.getLogger(), section);
    }

    static DbConfig load(String pluginName, File dataFolder, Logger logger, ConfigurationSection section) {
        String defaultDatabase = pluginName.toLowerCase(Locale.ROOT);
        if (section == null) {
            return new DbConfig(Type.SQLITE, new File(dataFolder, DEFAULT_FILE), "localhost", DEFAULT_PORT,
                    defaultDatabase, "root", "", DEFAULT_POOL_SIZE, false, DEFAULT_CONNECT_TIMEOUT,
                    DEFAULT_SOCKET_TIMEOUT);
        }
        return new DbConfig(
                type(section.getString("type"), logger),
                sqliteFile(dataFolder, section.getString("file")),
                text(section, "host", "localhost"),
                section.getInt("port", DEFAULT_PORT),
                text(section, "database", defaultDatabase),
                text(section, "username", "root"),
                section.getString("password", ""),
                Math.max(1, section.getInt("pool-size", DEFAULT_POOL_SIZE)),
                section.getBoolean("ssl", false),
                clamp(section.getInt("connect-timeout-seconds", DEFAULT_CONNECT_TIMEOUT), 1),
                clamp(section.getInt("socket-timeout-seconds", DEFAULT_SOCKET_TIMEOUT), 0));
    }

    private static Type type(String raw, Logger logger) {
        if (raw == null || raw.isBlank()) {
            return Type.SQLITE;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "sqlite" -> Type.SQLITE;
            case "mysql" -> Type.MYSQL;
            default -> {
                logger.warning("Unknown database.type '" + raw + "'; using sqlite.");
                yield Type.SQLITE;
            }
        };
    }

    private static File sqliteFile(File dataFolder, String raw) {
        String name = raw == null || raw.isBlank() ? DEFAULT_FILE : raw.trim();
        File file = new File(name);
        return file.isAbsolute() ? file : new File(dataFolder, name);
    }

    private static String text(ConfigurationSection section, String key, String def) {
        String value = section.getString(key);
        return value == null || value.isBlank() ? def : value.trim();
    }

    private static int clamp(int seconds, int min) {
        return Math.max(min, Math.min(MAX_TIMEOUT, seconds));
    }

    public Type type() {
        return type;
    }

    /** The SQLite file, absolute or under the plugin folder (used only with {@link Type#SQLITE}). */
    public File sqliteFile() {
        return sqliteFile;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String database() {
        return database;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    /** Connections (and database threads) for MySQL; SQLite always uses one. */
    public int poolSize() {
        return poolSize;
    }

    public boolean ssl() {
        return ssl;
    }

    public int connectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    /** MySQL read timeout; 0 means no limit. */
    public int socketTimeoutSeconds() {
        return socketTimeoutSeconds;
    }

    /** The JDBC URL of this configuration. */
    String jdbcUrl() {
        if (type == Type.SQLITE) {
            return "jdbc:sqlite:" + sqliteFile.getAbsolutePath();
        }
        // allowPublicKeyRetrieval and characterEncoding keep MySQL 8 (caching_sha2_password, no SSL) working.
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + ssl
                + "&allowPublicKeyRetrieval=true"
                + "&characterEncoding=utf8"
                + "&connectTimeout=" + connectTimeoutSeconds * 1000L
                + "&socketTimeout=" + socketTimeoutSeconds * 1000L;
    }

    /** Database threads and pooled connections: one for SQLite, {@link #poolSize()} for MySQL. */
    int threads() {
        return type == Type.SQLITE ? 1 : poolSize;
    }

    /** Never shows the password. */
    @Override
    public String toString() {
        return type == Type.SQLITE ? "sqlite " + sqliteFile
                : "mysql " + username + "@" + host + ":" + port + "/" + database + (ssl ? " (ssl)" : "");
    }
}
