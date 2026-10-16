package com.rs.lib.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.rs.lib.Ph;
import com.rs.lib.db.FakeDbHost;
import com.rs.lib.db.RsDb;
import com.rs.lib.db.TestDbs;

import net.kyori.adventure.text.Component;

/** Player settings on a real SQLite database, without a server. */
class SettingsManagerTest {

    @TempDir
    Path dir;

    private RsDb db;
    private final List<SettingsManager> managers = new ArrayList<>();

    private static final PlayerOption PMS = PlayerOption.toggle("private-messages", true)
            .name("&aPrivate messages").icon(Material.PAPER).build();
    private static final PlayerOption MENTIONS = PlayerOption.choice("mentions", "all", "all", "friends", "none")
            .name("Mentions").icon(Material.BELL).build();
    private static final PlayerOption DISTANCE = PlayerOption.number("distance", 8, 2, 16).step(4)
            .name("Distance").icon(Material.SPYGLASS).build();
    private static final PlayerOption STAFF = PlayerOption.toggle("staff-alerts", false)
            .name("Staff alerts").icon(Material.BEACON).permission("test.staff").build();

    @BeforeEach
    void open() {
        db = TestDbs.sqlite(dir.toFile(), new FakeDbHost());
    }

    @AfterEach
    void close() {
        managers.forEach(SettingsManager::shutdown);
        db.shutdown();
    }

    private SettingsManager manager(FakeSettingsHost host) {
        SettingsManager manager = new SettingsManager(host);
        managers.add(manager);
        return manager;
    }

    private static void idle(SettingsManager manager) {
        manager.idle().orTimeout(5, TimeUnit.SECONDS).join();
    }

    private int rows() {
        return TestDbs.count(db, SettingsStore.TABLE);
    }

    private List<String> storedValues() {
        return db.query("SELECT setting, setting_value FROM " + SettingsStore.TABLE + " ORDER BY setting", null, r -> {
            List<String> list = new ArrayList<>();
            while (r.next()) {
                list.add(r.getString(1) + "=" + r.getString(2));
            }
            return list;
        }).join();
    }

    @Test
    void onlyValuesThatDifferFromTheDefaultAreStored() {
        FakeSettingsHost host = new FakeSettingsHost("Chat", db);
        Player alex = host.player("Alex");
        host.online.add(alex);
        SettingsManager settings = manager(host);
        settings.register(PMS);
        settings.register(MENTIONS);
        settings.register(DISTANCE);
        idle(settings);
        assertTrue(settings.isLoaded(alex.getUniqueId()), "players online at the first registration are read");
        assertEquals(0, rows());
        assertTrue(settings.enabled(alex, "private-messages"));

        assertTrue(settings.set(alex, "private-messages", "FALSE"));
        assertTrue(settings.set(alex, "mentions", "Friends"));
        assertTrue(settings.set(alex, "distance", "8"));
        assertFalse(settings.enabled(alex, "private-messages"), "reads are memory, at once");
        assertEquals("friends", settings.value(alex, "mentions"));
        idle(settings);
        assertEquals(List.of("mentions=friends", "private-messages=false"), storedValues(),
                "the distance is its default: no row");

        assertTrue(settings.set(alex, "private-messages", "true"));
        idle(settings);
        assertEquals(List.of("mentions=friends"), storedValues(), "back to the default deletes the row");

        assertFalse(settings.set(alex, "mentions", "nobody"), "not one of the values");
        assertFalse(settings.set(alex, "distance", "17"), "above max");
        assertFalse(settings.set(alex, "unknown", "1"));
        assertThrows(IllegalArgumentException.class, () -> settings.value(alex, "unknown"));
        assertThrows(IllegalArgumentException.class, () -> settings.number(alex, "mentions"));
    }

    @Test
    void valuesComeBackOnJoinAndLeaveOnQuit() {
        FakeSettingsHost host = new FakeSettingsHost("Chat", db);
        Player alex = host.player("Alex");
        SettingsManager before = manager(host);
        before.register(MENTIONS);
        before.register(DISTANCE);
        assertTrue(before.set(alex.getUniqueId(), "mentions", "none"), "an offline player can be set by id");
        assertTrue(before.set(alex.getUniqueId(), "distance", "12"));
        idle(before);
        before.shutdown();

        SettingsManager after = manager(host);
        after.register(MENTIONS);
        after.register(DISTANCE);
        assertEquals("all", after.value(alex, "mentions"), "the default until the join read is back");
        after.load(alex.getUniqueId());
        idle(after);
        assertEquals("none", after.value(alex, "mentions"));
        assertEquals(12, after.number(alex, "distance"));

        after.forget(alex.getUniqueId());
        assertFalse(after.isLoaded(alex.getUniqueId()));
        assertEquals("all", after.value(alex, "mentions"), "dropped on quit");
    }

    @Test
    void aValueSetWhileTheJoinReadIsOutWins() throws Exception {
        FakeSettingsHost host = new FakeSettingsHost("Chat", db);
        Player alex = host.player("Alex");
        SettingsManager seed = manager(host);
        seed.register(MENTIONS);
        seed.set(alex.getUniqueId(), "mentions", "none");
        idle(seed);
        seed.shutdown();

        CountDownLatch gate = new CountDownLatch(1);
        // SQLite runs one job at a time: everything queued after this waits for the gate.
        db.query("SELECT 1", statement -> {
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, rows -> null);
        SettingsManager settings = manager(host);
        settings.register(MENTIONS);
        settings.load(alex.getUniqueId());
        assertTrue(settings.set(alex, "mentions", "friends"));
        gate.countDown();
        idle(settings);
        assertTrue(settings.isLoaded(alex.getUniqueId()));
        assertEquals("friends", settings.value(alex, "mentions"), "the newer value beats the row");
        assertEquals(List.of("mentions=friends"), storedValues());
    }

    @Test
    void storedValuesTheOptionCannotHoldReadAsTheDefault() {
        FakeSettingsHost host = new FakeSettingsHost("Chat", db);
        Player alex = host.player("Alex");
        SettingsManager settings = manager(host);
        settings.register(DISTANCE);
        idle(settings);
        db.upsert(SettingsStore.TABLE).keys("owner", "Chat").keys("uuid", alex.getUniqueId().toString())
                .keys("setting", "distance").set("setting_value", "banana").run().join();
        settings.load(alex.getUniqueId());
        idle(settings);
        assertEquals(8, settings.number(alex, "distance"));
    }

    @Test
    void optionsBehindAPermissionAreHiddenAndReadTheirDefault() {
        FakeSettingsHost host = new FakeSettingsHost("Staff", db);
        Player member = host.player("Member");
        Player moderator = host.player("Moderator", "test.staff");
        SettingsManager settings = manager(host);
        settings.register(PMS);
        settings.register(STAFF);
        settings.load(member.getUniqueId());
        settings.load(moderator.getUniqueId());
        idle(settings);

        assertEquals(List.of("private-messages"), keys(SettingsManager.entriesFor(member)));
        assertEquals(List.of("private-messages", "staff-alerts"), keys(SettingsManager.entriesFor(moderator)));

        assertFalse(settings.set(member, "staff-alerts", "true"), "without the permission nothing changes");
        assertFalse(settings.click(member, "staff-alerts", true));
        assertTrue(settings.set(member.getUniqueId(), "staff-alerts", "true"), "by id (admin, import): no check");
        assertFalse(settings.enabled(member, "staff-alerts"), "a player without the permission reads the default");
        assertTrue(settings.click(moderator, "staff-alerts", true));
        assertTrue(settings.enabled(moderator, "staff-alerts"));
    }

    @Test
    void theMenuListsEveryPluginInRegistrationOrderAndClicksMoveValues() {
        FakeSettingsHost chatHost = new FakeSettingsHost("Chat", db);
        FakeSettingsHost killsHost = new FakeSettingsHost("Kills", db);
        Player alex = chatHost.player("Alex");
        SettingsManager chat = manager(chatHost);
        SettingsManager kills = manager(killsHost);
        chat.register(MENTIONS);
        kills.register(DISTANCE);
        chat.register(PMS);
        chat.load(alex.getUniqueId());
        kills.load(alex.getUniqueId());
        idle(chat);
        idle(kills);
        assertEquals(List.of("mentions", "private-messages", "distance"), keys(SettingsManager.entriesFor(alex)));

        assertTrue(chat.click(alex, "mentions", false), "right click goes back, wrapping");
        assertEquals("none", chat.value(alex, "mentions"));
        assertTrue(chat.click(alex, "mentions", true));
        assertEquals("all", chat.value(alex, "mentions"));
        assertTrue(chat.click(alex, "private-messages", true));
        assertFalse(chat.enabled(alex, "private-messages"));
        assertTrue(kills.click(alex, "distance", true));
        assertTrue(kills.click(alex, "distance", true));
        assertEquals(16, kills.number(alex, "distance"));
        assertFalse(kills.click(alex, "distance", true), "already at max");
        idle(chat);
        idle(kills);
        assertEquals(List.of("distance=16", "private-messages=false"), storedValues());

        kills.shutdown();
        assertEquals(List.of("mentions", "private-messages"), keys(SettingsManager.entriesFor(alex)),
                "a plugin that stopped leaves the menu");
    }

    @Test
    void aChannelSkipsThePlayersWhoMutedIt() {
        FakeSettingsHost host = new FakeSettingsHost("Kills", db);
        Player alex = host.player("Alex");
        Player sam = host.player("Sam");
        host.online.add(alex);
        host.online.add(sam);
        SettingsManager settings = manager(host);
        BroadcastChannel deaths = settings.channel(PlayerOption.toggle("death-messages", true)
                .name("Death messages").icon(Material.SKELETON_SKULL).build());
        idle(settings);
        assertEquals("death-messages", deaths.key());
        assertTrue(settings.click(sam, "death-messages", true), "Sam mutes it");

        assertEquals(1, deaths.send(Component.text("Alex died")));
        assertEquals(List.of("Alex: Alex died", "CONSOLE: Alex died"), host.sent);

        host.sent.clear();
        assertEquals(1, deaths.send("deaths.broadcast", Ph.of("player", "Sam")));
        assertEquals(List.of("Alex: deaths.broadcast player=Sam", "CONSOLE: deaths.broadcast player=Sam"), host.sent);
        assertEquals(List.of(alex), deaths.recipients());

        assertThrows(IllegalArgumentException.class, () -> settings.channel(MENTIONS));
    }

    @Test
    void registeringNeedsTheDatabase() {
        SettingsManager settings = manager(new FakeSettingsHost("NoDb", null));
        assertThrows(UnsupportedOperationException.class, () -> settings.register(PMS));
        assertTrue(SettingsManager.entriesFor(new FakeSettingsHost("NoDb", null).player("Alex")).isEmpty());
        settings.shutdown();
        assertThrows(IllegalStateException.class, () -> settings.register(PMS));
    }

    @Test
    void optionRules() {
        assertEquals("true", PMS.next("false", true));
        assertEquals("false", PMS.next("garbage", false), "an invalid value counts as the default");
        assertEquals("friends", MENTIONS.next("all", true));
        assertEquals("all", MENTIONS.next("none", true));
        assertEquals("4", DISTANCE.next("8", false));
        assertEquals("2", DISTANCE.next("4", false), "clamped at min");
        assertEquals("16", DISTANCE.next("14", true), "clamped at max");
        assertEquals("true", PMS.normalize(" TRUE "));
        assertEquals(null, PMS.normalize("yes"));
        assertEquals(null, DISTANCE.normalize("1"));
        assertNotEquals(null, DISTANCE.normalize("16"));
        assertThrows(IllegalArgumentException.class, () -> PlayerOption.choice("c", "x", "a", "b"));
        assertThrows(IllegalArgumentException.class, () -> PlayerOption.choice("c", "a", "a"));
        assertThrows(IllegalArgumentException.class, () -> PlayerOption.number("n", 20, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> PlayerOption.toggle("bad key", true));
        assertThrows(IllegalStateException.class, () -> PlayerOption.toggle("t", true).step(2));
        assertThrows(IllegalStateException.class, () -> PlayerOption.toggle("t", true).name("T").build());
    }

    @Test
    void pagesOfTheMenu() {
        assertEquals(1, SettingsMenu.pages(0));
        assertEquals(1, SettingsMenu.pages(45));
        assertEquals(2, SettingsMenu.pages(46));
        assertEquals(45, SettingsMenu.indexAt(1, 0, 46));
        assertEquals(-1, SettingsMenu.indexAt(1, 1, 46));
        assertEquals(-1, SettingsMenu.indexAt(0, SettingsMenu.CLOSE, 100));
        assertTrue(SettingsHost.Defaults.get("rslib.settings.menu-title").contains("Settings"));
    }

    private static List<String> keys(List<SettingsMenu.Entry> entries) {
        return entries.stream().map(entry -> entry.option().key()).toList();
    }

}
