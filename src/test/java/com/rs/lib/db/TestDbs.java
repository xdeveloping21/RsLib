package com.rs.lib.db;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Real SQLite databases for tests, in a temporary folder. */
public final class TestDbs {

    private TestDbs() {
    }

    /** A database in {@code folder/database.db} for {@code host}. */
    public static RsDb sqlite(File folder, FakeDbHost host) {
        return open(host, DbConfig.load(host.name(), folder, host.logger(), null));
    }

    public static RsDb open(FakeDbHost host, DbConfig config) {
        RsDb db = new RsDb(host, config);
        host.db = db;
        return db;
    }

    public static DbConfig config(String pluginName, File folder, ConfigurationSection section) {
        return DbConfig.load(pluginName, folder, Logger.getAnonymousLogger(), section);
    }

    /** Environment variable of the optional MySQL/MariaDB tests: {@code user:password@host:port/database}. */
    public static final String MYSQL_ENV = "RSLIB_TEST_MYSQL";

    /** A database on the server named by {@link #MYSQL_ENV}, with {@code poolSize} connections. */
    public static RsDb mysql(FakeDbHost host, int poolSize) {
        String spec = System.getenv(MYSQL_ENV);
        int at = spec.lastIndexOf('@');
        String[] credentials = spec.substring(0, at).split(":", 2);
        String[] server = spec.substring(at + 1).split("/", 2);
        String[] hostPort = server[0].split(":", 2);
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection section = yaml.createSection("database");
        section.set("type", "mysql");
        section.set("host", hostPort[0]);
        section.set("port", Integer.parseInt(hostPort[1]));
        section.set("database", server[1]);
        section.set("username", credentials[0]);
        section.set("password", credentials.length > 1 ? credentials[1] : "");
        section.set("pool-size", poolSize);
        section.set("connect-timeout-seconds", 5);
        return open(host, DbConfig.load(host.name(), new File("."), host.logger(), section));
    }

    /** Wraps a plain future for {@code host}. */
    public static <T> RsFuture<T> future(FakeDbHost host, CompletableFuture<T> future, boolean mainCompleted) {
        return new RsFuture<>(host, future, mainCompleted);
    }

    /** Counts the rows of {@code table}, waiting for the answer. */
    public static int count(RsDb db, String table) {
        return db.query("SELECT COUNT(*) FROM " + table, null, rows -> rows.next() ? rows.getInt(1) : 0).join();
    }
}
