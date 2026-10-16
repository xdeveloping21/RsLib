package com.rs.lib.account;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.rs.lib.db.FakeDbHost;
import com.rs.lib.db.RsDb;
import com.rs.lib.db.TestDbs;
import com.rs.lib.internal.AltsAdmin;

/** Alt-account records: hashed addresses, the salt file, lookups and the purge. */
class AccountsTest {

    private static final long DAY = TimeUnit.DAYS.toMillis(1);
    private static final long NOW = 1_800_000_000_000L;
    private static final UUID ALEX = new UUID(0, 1);
    private static final UUID BLAKE = new UUID(0, 2);
    private static final UUID CASEY = new UUID(0, 3);
    private static final UUID DANA = new UUID(0, 4);

    @TempDir
    Path dir;

    private final List<RsDb> opened = new ArrayList<>();

    @AfterEach
    void close() {
        opened.forEach(RsDb::shutdown);
    }

    private Accounts accounts(int keepDays) throws IOException {
        FakeDbHost host = new FakeDbHost();
        host.name = "RsLib";
        RsDb db = TestDbs.sqlite(dir.toFile(), host);
        opened.add(db);
        db.migrate(Accounts.schema()).join();
        return new Accounts(db, AccountSalt.loadOrCreate(dir.resolve(Accounts.SALT_FILE)), keepDays, host.logger());
    }

    private static InetAddress ip(String text) throws IOException {
        return InetAddress.getByName(text);
    }

    @Test
    void theHashIsSaltedSha256OfTheAddress() {
        byte[] salt = new byte[AccountSalt.BYTES];
        String hash = Accounts.hash(salt, "203.0.113.7");
        assertTrue(hash.matches("[0-9a-f]{64}"), hash);
        assertEquals(hash, Accounts.hash(salt, "203.0.113.7"));
        assertNotEquals(hash, Accounts.hash(salt, "203.0.113.8"));
        byte[] other = salt.clone();
        other[0] = 1;
        assertNotEquals(hash, Accounts.hash(other, "203.0.113.7"), "another server hashes the same address differently");
    }

    /** Trap: the address must not reach the database in any form that can be read back. */
    @Test
    void theRawAddressIsNeverStored() throws IOException {
        Accounts accounts = accounts(30);
        accounts.record(ALEX, "Alex", ip("203.0.113.7"), NOW).join();
        accounts.record(BLAKE, "Blake", ip("2001:db8::1"), NOW).join();

        RsDb db = opened.get(0);
        List<String> values = db.query("SELECT * FROM " + Accounts.TABLE, null, rows -> {
            List<String> all = new ArrayList<>();
            while (rows.next()) {
                for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                    all.add(rows.getString(i));
                }
            }
            return all;
        }).join();
        assertEquals(8, values.size());
        assertTrue(values.contains(Accounts.hash(AccountSalt.loadOrCreate(dir.resolve(Accounts.SALT_FILE)),
                "203.0.113.7")));
        for (String value : values) {
            assertFalse(value.contains("203.0.113") || value.contains("2001:db8"), value);
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().startsWith("database.db")).toList()) {
                String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                assertFalse(bytes.contains("203.0.113.7"), file + " holds the address");
            }
        }
    }

    @Test
    void theSaltIsCreatedOnceAndKept() throws IOException {
        Path file = dir.resolve("sub").resolve(Accounts.SALT_FILE);
        byte[] first = AccountSalt.loadOrCreate(file);
        assertEquals(AccountSalt.BYTES, first.length);
        assertTrue(Files.readString(file).trim().matches("[0-9a-f]{64}"));
        assertArrayEquals(first, AccountSalt.loadOrCreate(file));
        assertFalse(java.util.Arrays.equals(first, AccountSalt.loadOrCreate(dir.resolve("other.key"))),
                "each server gets its own");
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
    }

    /** Trap: a damaged salt is reported and left alone; a new one would orphan every record. */
    @Test
    void aDamagedSaltIsNeverReplaced() throws IOException {
        Path file = dir.resolve(Accounts.SALT_FILE);
        Files.writeString(file, "not a salt\n");
        IOException error = assertThrows(IOException.class, () -> AccountSalt.loadOrCreate(file));
        assertTrue(error.getMessage().contains("does not hold a salt"), error.getMessage());
        assertEquals("not a salt\n", Files.readString(file));
    }

    @Test
    void altsAreTheAccountsThatSharedAnAddress() throws IOException {
        Accounts accounts = accounts(30);
        accounts.record(ALEX, "Alex", ip("203.0.113.7"), NOW - 5 * DAY).join();
        accounts.record(BLAKE, "Blake", ip("203.0.113.7"), NOW - 3 * DAY).join();
        accounts.record(CASEY, "Casey", ip("198.51.100.1"), NOW).join();
        accounts.record(ALEX, "Alex", ip("192.0.2.9"), NOW - DAY).join();
        accounts.record(DANA, "Dana", ip("192.0.2.9"), NOW - 2 * DAY).join();
        accounts.record(DANA, "Dana", ip("203.0.113.7"), NOW - 4 * DAY).join();

        assertEquals(List.of(new AltAccount(DANA, "Dana", NOW - 2 * DAY), new AltAccount(BLAKE, "Blake", NOW - 3 * DAY)),
                accounts.alts(ALEX).join());
        assertEquals(List.of(new AltAccount(DANA, "Dana", NOW - 4 * DAY), new AltAccount(ALEX, "Alex", NOW - 5 * DAY)),
                accounts.alts(BLAKE).join(), "last seen at a shared address, not anywhere");
        assertEquals(List.of(), accounts.alts(CASEY).join());
        assertEquals(List.of(), accounts.alts(new UUID(9, 9)).join());

        accounts.record(BLAKE, "Blake2", ip("198.51.100.200"), NOW).join();
        assertEquals("Blake2", accounts.alts(ALEX).join().get(1).name(), "a new name replaces the old one everywhere");
        assertEquals(Optional.of(BLAKE), accounts.lookup("BLAKE2").join());
        assertEquals(Optional.empty(), accounts.lookup("Blake").join());
    }

    @Test
    void thePurgeDeletesWhatWasNotSeenForKeepDays() throws IOException {
        Accounts accounts = accounts(30);
        accounts.record(ALEX, "Alex", ip("203.0.113.7"), NOW - 31 * DAY).join();
        accounts.record(BLAKE, "Blake", ip("203.0.113.7"), NOW - 29 * DAY).join();
        accounts.record(ALEX, "Alex", ip("192.0.2.9"), NOW - DAY).join();

        assertEquals(1, accounts.purge(NOW).join());
        assertEquals(List.of(), accounts.alts(BLAKE).join(), "the shared address of Alex is gone");
        assertEquals(2, TestDbs.count(opened.get(0), Accounts.TABLE));
        assertEquals(Optional.of(ALEX), accounts.lookup("alex").join());
    }

    @Test
    void offAnswersThatItIsOff() {
        Accounts off = Accounts.get();
        assertFalse(off.enabled());
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> off.alts(ALEX));
        assertTrue(error.getMessage().contains("accounts.enabled"));
        assertThrows(IllegalStateException.class, () -> off.lookup("Alex"));
    }

    @Test
    void theStaffCommandLines() {
        assertEquals(List.of("No join of Zed is recorded."), AltsAdmin.lines("Zed", null, NOW));
        assertEquals(List.of("Zed shares no address with another account."), AltsAdmin.lines("Zed", List.of(), NOW));
        assertEquals(List.of("Accounts that joined from an address of Alex (1):",
                        "- Dana (00000000-0000-0000-0000-000000000004), last seen 2d 3h ago"),
                AltsAdmin.lines("Alex", List.of(new AltAccount(DANA, "Dana", NOW - 2 * DAY - 3 * 3_600_000L)), NOW));
    }
}
