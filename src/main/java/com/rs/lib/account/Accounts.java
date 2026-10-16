package com.rs.lib.account;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.Rs;
import com.rs.lib.db.Migration;
import com.rs.lib.db.RsDb;
import com.rs.lib.db.RsFuture;
import com.rs.lib.yml.RsYml;

/**
 * Alt-account detection, shared by every plugin: {@code rs().accounts()}.
 *
 * <p>When a player joins, RsLib stores a salted SHA-256 hash of their IP address with their
 * UUID, name and the time, in RsLib's own database ({@code plugins/RsLib/database.db}, SQLite
 * unless RsLib's config.yml has a {@code database:} section). The address itself is never
 * stored. The salt is generated once per server in {@code plugins/RsLib/accounts-salt.key} and
 * never written to the database, so two servers hash the same address differently.</p>
 *
 * <pre>
 * Accounts accounts = rs().accounts();
 * if (accounts.enabled()) {
 *     accounts.alts(player.getUniqueId()).thenSync(alts -&gt; { ... });
 * }
 * </pre>
 *
 * <p>Records not seen for {@code accounts.keep-days} days are deleted at startup and once a
 * day. {@code accounts.enabled: false} in RsLib's config.yml stops recording; the queries then
 * throw. Queries run on RsLib's database thread; consume them with {@code thenSync}.</p>
 */
public final class Accounts {

    static final String TABLE = "rslib_accounts";
    static final String SALT_FILE = "accounts-salt.key";
    static final int DEFAULT_KEEP_DAYS = 90;
    private static final long DAY_MILLIS = TimeUnit.DAYS.toMillis(1);
    private static final long FIRST_PURGE_TICKS = 60 * 20L;
    private static final long PURGE_PERIOD_TICKS = 24 * 60 * 60 * 20L;
    /** Longest name kept, as in the player name directory. */
    private static final int MAX_NAME_LENGTH = 64;

    private static final Accounts OFF = new Accounts(null, null, DEFAULT_KEEP_DAYS, null);
    private static volatile Accounts current = OFF;

    private final RsDb db;
    private final byte[] salt;
    private final int keepDays;
    private final Logger log;
    private final AtomicBoolean warned = new AtomicBoolean();

    Accounts(RsDb db, byte[] salt, int keepDays, Logger log) {
        this.db = db;
        this.salt = salt == null ? null : salt.clone();
        this.keepDays = keepDays;
        this.log = log;
    }

    /** The detection as RsLib runs it now; a disabled one while it is off or RsLib is not enabled. */
    public static Accounts get() {
        return current;
    }

    /** False when {@code accounts.enabled} is false in RsLib's config.yml, or the salt could not be read. */
    public boolean enabled() {
        return db != null;
    }

    /** Days a record is kept after the account last joined from that address. */
    public int keepDays() {
        return keepDays;
    }

    /**
     * The other accounts that joined from any address {@code player} joined from, most recently
     * seen first. Empty for a player with no record or no shared address.
     *
     * @throws IllegalStateException when {@link #enabled()} is false
     */
    public RsFuture<List<AltAccount>> alts(UUID player) {
        Objects.requireNonNull(player, "player");
        RsDb database = requireDb();
        String id = player.toString();
        return database.query("SELECT other.uuid, MAX(other.name), MAX(other.last_seen) FROM " + TABLE + " mine JOIN "
                + TABLE + " other ON other.ip_hash = mine.ip_hash WHERE mine.uuid = ? AND other.uuid <> ?"
                + " GROUP BY other.uuid", statement -> {
                    statement.setString(1, id);
                    statement.setString(2, id);
                }, rows -> {
                    List<AltAccount> alts = new ArrayList<>();
                    while (rows.next()) {
                        UUID uuid = parse(rows.getString(1));
                        if (uuid != null) {
                            alts.add(new AltAccount(uuid, rows.getString(2), rows.getLong(3)));
                        }
                    }
                    alts.sort(Comparator.comparingLong(AltAccount::lastSeen).reversed()
                            .thenComparing(AltAccount::name));
                    return alts;
                });
    }

    /**
     * The account that joined most recently under {@code name} (ignoring case), for staff
     * commands about players who are offline.
     *
     * @throws IllegalStateException when {@link #enabled()} is false
     */
    public RsFuture<Optional<UUID>> lookup(String name) {
        Objects.requireNonNull(name, "name");
        RsDb database = requireDb();
        return database.query("SELECT uuid FROM " + TABLE + " WHERE LOWER(name) = ? ORDER BY last_seen DESC LIMIT 1",
                statement -> statement.setString(1, name.toLowerCase(Locale.ROOT)),
                rows -> rows.next() ? Optional.ofNullable(parse(rows.getString(1))) : Optional.empty());
    }

    // ------------------------------------------------------------------ recording

    /**
     * Records that {@code player} joined as {@code name} from {@code address} at {@code now}:
     * only the hash of the address is written. Its other records take the new name.
     */
    RsFuture<Void> record(UUID player, String name, InetAddress address, long now) {
        RsDb database = requireDb();
        String hash = hash(salt, address.getHostAddress());
        String id = player.toString();
        String shown = name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
        RsFuture<Void> write = database.transaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + TABLE + " SET name = ?, last_seen = ? WHERE uuid = ? AND ip_hash = ?")) {
                update.setString(1, shown);
                update.setLong(2, now);
                update.setString(3, id);
                update.setString(4, hash);
                if (update.executeUpdate() == 0) {
                    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + TABLE
                            + " (uuid, ip_hash, name, last_seen) VALUES (?, ?, ?, ?)")) {
                        insert.setString(1, id);
                        insert.setString(2, hash);
                        insert.setString(3, shown);
                        insert.setLong(4, now);
                        insert.executeUpdate();
                    }
                }
            }
            try (PreparedStatement rename = connection.prepareStatement(
                    "UPDATE " + TABLE + " SET name = ? WHERE uuid = ?")) {
                rename.setString(1, shown);
                rename.setString(2, id);
                rename.executeUpdate();
            }
        });
        return write.exceptionally(this::warnOnce);
    }

    /** Deletes the records last seen more than {@link #keepDays()} days before {@code now}. */
    RsFuture<Integer> purge(long now) {
        RsDb database = requireDb();
        long cutoff = now - keepDays * DAY_MILLIS;
        return database.update("DELETE FROM " + TABLE + " WHERE last_seen < ?",
                statement -> statement.setLong(1, cutoff)).exceptionally(this::warnOnce);
    }

    /** The hex SHA-256 of {@code salt} followed by the address text. */
    static String hash(byte[] salt, String address) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update(address.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this Java", e);
        }
    }

    /** The table and its indexes, created once (migration history owner: RsLib). */
    static Migration schema() {
        return Migration.of(1, "alt-account records", context -> {
            context.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (uuid VARCHAR(36) NOT NULL,"
                    + " ip_hash CHAR(64) NOT NULL, name VARCHAR(" + MAX_NAME_LENGTH + ") NOT NULL, last_seen BIGINT NOT NULL,"
                    + " PRIMARY KEY (uuid, ip_hash))");
            context.execute("CREATE INDEX " + TABLE + "_hash ON " + TABLE + " (ip_hash)");
            context.execute("CREATE INDEX " + TABLE + "_seen ON " + TABLE + " (last_seen)");
        });
    }

    private RsDb requireDb() {
        if (db == null) {
            throw new IllegalStateException("Alt-account detection is off (accounts.enabled in RsLib's config.yml)");
        }
        return db;
    }

    private void warnOnce(Throwable error) {
        if (log != null && warned.compareAndSet(false, true)) {
            log.log(Level.WARNING, "Alt-account records could not be written or purged (further errors are not shown"
                    + " until the next reload)", error);
        }
    }

    private static UUID parse(String text) {
        try {
            return text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ lifecycle (RsLib only)

    /**
     * Starts the detection for RsLib: reads {@code accounts:} from RsLib's config.yml (again
     * on every reload), records joins and purges old records once a day. Not for plugins.
     */
    public static synchronized void start(JavaPlugin rslib, Rs own) {
        RsYml config = own.yml().config();
        apply(rslib, own, config);
        config.onReload(() -> apply(rslib, own, config));
        rslib.getServer().getPluginManager().registerEvents(new JoinListener(), rslib);
        own.scheduler().timer(FIRST_PURGE_TICKS, PURGE_PERIOD_TICKS, () -> {
            Accounts now = current;
            if (now.enabled()) {
                now.purge(System.currentTimeMillis());
            }
        });
    }

    /** Stops recording; the queries throw from now on. Called by RsLib when it disables. */
    public static synchronized void stop() {
        current = OFF;
    }

    private static synchronized void apply(JavaPlugin rslib, Rs own, RsYml config) {
        Logger log = rslib.getLogger();
        if (!config.getBoolean("accounts.enabled", true)) {
            current = OFF;
            return;
        }
        int keepDays = config.getInt("accounts.keep-days", DEFAULT_KEEP_DAYS);
        if (keepDays < 1) {
            log.warning("accounts.keep-days must be at least 1 (it is " + keepDays + "); using 1.");
            keepDays = 1;
        }
        byte[] salt;
        try {
            salt = AccountSalt.loadOrCreate(rslib.getDataFolder().toPath().resolve(SALT_FILE));
        } catch (IOException e) {
            log.warning("Alt-account detection is off: " + e.getMessage());
            current = OFF;
            return;
        }
        RsDb db = own.db();
        boolean wasOff = !current.enabled();
        db.migrate(schema()).exceptionally(error -> log.log(Level.WARNING,
                "Alt-account records are unavailable: their table could not be created", error));
        Accounts started = new Accounts(db, salt, keepDays, log);
        current = started;
        if (wasOff) {
            long now = System.currentTimeMillis();
            for (Player player : Bukkit.getOnlinePlayers()) {
                started.recordJoin(player, now);
            }
        }
    }

    private void recordJoin(Player player, long now) {
        InetSocketAddress socket = player.getAddress();
        InetAddress address = socket == null ? null : socket.getAddress();
        if (address != null) {
            record(player.getUniqueId(), player.getName(), address, now);
        }
    }

    private static final class JoinListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(PlayerJoinEvent event) {
            Accounts now = current;
            if (now.enabled()) {
                now.recordJoin(event.getPlayer(), System.currentTimeMillis());
            }
        }
    }
}
