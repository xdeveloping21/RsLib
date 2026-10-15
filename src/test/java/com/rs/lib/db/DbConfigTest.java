package com.rs.lib.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.rs.lib.yml.YmlTestSupport.Captured;
import com.zaxxer.hikari.HikariConfig;

/** The {@code database:} section: same keys, defaults, file name and URLs as the data servers already have. */
class DbConfigTest {

    @TempDir
    Path dir;

    private final Captured log = new Captured();

    private static ConfigurationSection section(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (InvalidConfigurationException e) {
            throw new AssertionError(e);
        }
        return config.getConfigurationSection("database");
    }

    private DbConfig load(String yaml) {
        return DbConfig.load("RsTrade", dir.toFile(), log.logger, yaml == null ? null : section(yaml));
    }

    /** Trap: same SQLite file and the plugin name in lower case as default MySQL database. */
    @Test
    void withoutSectionSqliteInDatabaseDbAndLowerCasePluginName() {
        DbConfig config = load(null);
        assertEquals(DbConfig.Type.SQLITE, config.type());
        assertEquals(new File(dir.toFile(), "database.db"), config.sqliteFile());
        assertEquals("rstrade", config.database());
        assertEquals("localhost", config.host());
        assertEquals(3306, config.port());
        assertEquals("root", config.username());
        assertEquals("", config.password());
        assertEquals(4, config.poolSize());
        assertFalse(config.ssl());
        assertEquals(10, config.connectTimeoutSeconds());
        assertEquals(30, config.socketTimeoutSeconds());
        assertEquals("jdbc:sqlite:" + new File(dir.toFile(), "database.db").getAbsolutePath(), config.jdbcUrl());
        assertTrue(log.warnings().isEmpty());
    }

    @Test
    void publishedKeysOnlyKeepTheOtherDefaults() {
        DbConfig config = load("database:\n  type: mysql\n  host: db.example.net\n  port: 3307\n"
                + "  database: network\n  username: rs\n  password: secret\n");
        assertEquals(DbConfig.Type.MYSQL, config.type());
        assertEquals("db.example.net", config.host());
        assertEquals(3307, config.port());
        assertEquals("network", config.database());
        assertEquals("rs", config.username());
        assertEquals("secret", config.password());
        assertEquals(4, config.poolSize());
        assertEquals(10, config.connectTimeoutSeconds());
        assertEquals(30, config.socketTimeoutSeconds());
        assertEquals(4, config.threads());
        assertFalse(config.toString().contains("secret"));
    }

    @Test
    void emptySectionMeansDefaults() {
        DbConfig config = load("database:\n  file: ''\n  database: ''\n");
        assertEquals(DbConfig.Type.SQLITE, config.type());
        assertEquals(new File(dir.toFile(), "database.db"), config.sqliteFile());
        assertEquals("rstrade", config.database());
        assertEquals(1, config.threads());
    }

    @Test
    void sqliteFileRelativeToPluginFolderOrAbsolute() {
        assertEquals(new File(dir.toFile(), "data/store.db"), load("database:\n  file: data/store.db\n").sqliteFile());
        File absolute = dir.resolve("elsewhere/other.db").toAbsolutePath().toFile();
        assertEquals(absolute, load("database:\n  file: '" + absolute.getPath() + "'\n").sqliteFile());
    }

    @Test
    void unknownTypeWarnsAndUsesSqlite() {
        assertEquals(DbConfig.Type.MYSQL, load("database:\n  type: MySQL\n").type());
        assertTrue(log.warnings().isEmpty());
        assertEquals(DbConfig.Type.SQLITE, load("database:\n  type: postgres\n").type());
        assertEquals(List.of("Unknown database.type 'postgres'; using sqlite."), log.warnings());
    }

    @Test
    void poolAndTimeoutsAreClamped() {
        DbConfig low = load("database:\n  type: mysql\n  pool-size: 0\n  connect-timeout-seconds: 0\n"
                + "  socket-timeout-seconds: -5\n");
        assertEquals(1, low.poolSize());
        assertEquals(1, low.connectTimeoutSeconds());
        assertEquals(0, low.socketTimeoutSeconds());
        DbConfig high = load("database:\n  connect-timeout-seconds: 99999\n  socket-timeout-seconds: 99999\n");
        assertEquals(3600, high.connectTimeoutSeconds());
        assertEquals(3600, high.socketTimeoutSeconds());
    }

    /** Trap: without these flags MySQL 8 (caching_sha2_password, no SSL) refuses the connection. */
    @Test
    void mysqlUrlKeepsCompatibilityFlags() {
        DbConfig config = load("database:\n  type: mysql\n  host: h\n  port: 3306\n  database: d\n"
                + "  connect-timeout-seconds: 7\n  socket-timeout-seconds: 0\n  ssl: true\n");
        assertEquals("jdbc:mysql://h:3306/d?useSSL=true&allowPublicKeyRetrieval=true&characterEncoding=utf8"
                + "&connectTimeout=7000&socketTimeout=0", config.jdbcUrl());
    }

    /** Trap: SQLite stays in WAL mode with a busy timeout and a single connection. */
    @Test
    void sqlitePoolProfile() {
        HikariConfig hikari = RsDb.hikariConfig(load(null), "RsTrade-db");
        assertEquals("org.sqlite.JDBC", hikari.getDriverClassName());
        assertEquals(1, hikari.getMaximumPoolSize());
        assertEquals("WAL", hikari.getDataSourceProperties().getProperty("journal_mode"));
        assertEquals("5000", hikari.getDataSourceProperties().getProperty("busy_timeout"));
        assertEquals(10_000L, hikari.getConnectionTimeout());
        assertEquals(5_000L, hikari.getValidationTimeout());
        assertEquals("RsTrade-db", hikari.getPoolName());
    }

    @Test
    void mysqlPoolProfile() {
        HikariConfig hikari = RsDb.hikariConfig(load("database:\n  type: mysql\n  pool-size: 6\n"
                + "  username: u\n  password: p\n  connect-timeout-seconds: 2\n"), "RsTrade-db");
        assertEquals("com.mysql.cj.jdbc.Driver", hikari.getDriverClassName());
        assertEquals(6, hikari.getMaximumPoolSize());
        assertEquals("u", hikari.getUsername());
        assertEquals("p", hikari.getPassword());
        assertEquals("true", hikari.getDataSourceProperties().getProperty("cachePrepStmts"));
        assertEquals("250", hikari.getDataSourceProperties().getProperty("prepStmtCacheSize"));
        assertEquals("2048", hikari.getDataSourceProperties().getProperty("prepStmtCacheSqlLimit"));
        assertEquals(2_000L, hikari.getConnectionTimeout());
        assertEquals(2_000L, hikari.getValidationTimeout(), "the liveness check fits in a short connect budget");
    }
}
