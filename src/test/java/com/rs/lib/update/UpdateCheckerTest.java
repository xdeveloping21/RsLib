package com.rs.lib.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;
import com.rs.lib.RsSpec;
import com.rs.lib.update.internal.FakeTransport;
import com.rs.lib.update.internal.ReleaseCache;
import com.rs.lib.update.internal.SiteClient;
import com.rs.lib.yml.YmlTestSupport.Captured;

/** {@link UpdateChecker} without a server: one check at a time, against fixtures. */
class UpdateCheckerTest {

    private static final String ID = "myplugin";
    private static final String URL = "https://rsdevelopment.dev/v1/releases/" + ID;
    /** Longer than the cache keeps an answer. */
    private static final long LATER = 6L * 60L * 1_000_000_000L;

    private final Captured log = new Captured();
    private final FakeTransport http = new FakeTransport();
    private final AtomicLong now = new AtomicLong();
    private final ReleaseCache cache = new ReleaseCache(now::get);
    private final SiteClient site = new SiteClient(http, "RsLib/1.0.0");
    private final AtomicReference<String> installed = new AtomicReference<>("1.2.0");
    private final UpdateChecker checker = checker("MyPlugin", log);

    private UpdateChecker checker(String plugin, Captured logs) {
        return new UpdateChecker(null, plugin, installed::get, logs.logger, site, cache);
    }

    /** Publishes {@code version} and lets the cached answer expire. */
    private void publish(String version) {
        http.answer(URL, FakeTransport.release(ID, version));
        now.addAndGet(LATER);
    }

    @Test
    void newerVersionIsReportedOnceUntilItChanges() {
        publish("1.3.0");
        checker.check(ID);
        now.addAndGet(LATER);
        checker.check(ID);
        assertEquals(List.of("Version 1.3.0 available, installed 1.2.0."), log.messages(Level.INFO));
        assertEquals(new UpdateChecker.Finding(ID, "1.3.0"), checker.findings().get(ID));

        publish("1.4.0");
        checker.check(ID);
        assertEquals(2, log.messages(Level.INFO).size());
        assertEquals("1.4.0", checker.findings().get(ID).version());

        // Up to date again: the finding goes away without a word.
        installed.set("1.4.0");
        now.addAndGet(LATER);
        checker.check(ID);
        assertTrue(checker.findings().isEmpty());
        assertEquals(2, log.messages(Level.INFO).size());
    }

    @Test
    void sameOrOlderOrUnparsableIsNotReported() {
        for (String version : List.of("1.2.0", "1.1.9", "1.2.0-rc.1")) {
            publish(version);
            checker.check(ID);
        }
        installed.set("dev-build");
        publish("9.0.0");
        checker.check(ID);
        assertTrue(log.records.isEmpty());
        assertTrue(checker.findings().isEmpty());
    }

    @Test
    void requestsGoToTheSiteWithTheRsLibUserAgent() {
        publish("1.3.0");
        checker.check(ID);
        assertEquals(URL, http.sent.get(0).url());
        Map<String, String> headers = http.sent.get(0).headers();
        assertEquals("RsLib/1.0.0", headers.get("User-Agent"));
        assertEquals("application/json", headers.get("Accept"));
        assertFalse(headers.containsKey("Authorization"));
    }

    @Test
    void failuresWarnOncePerProduct() {
        // Nothing published: the site's 404.
        checker.check(ID);
        http.answer(URL, 503, "unavailable");
        checker.check(ID);
        http.answer(URL, "{\"product\":\"myplugin\"");
        checker.check(ID);
        http.answer(URL, "{\"product\":\"myplugin\",\"version\":\"nightly\"}");
        checker.check(ID);
        List<String> warnings = log.warnings();
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("HTTP 404") && warnings.get(0).contains("unknown product"),
                warnings.get(0));
        assertEquals(4, http.sent.size(), "failures are not cached");
        assertTrue(checker.findings().isEmpty());

        checker.check("other");
        assertEquals(2, log.warnings().size());

        // A new start warns again.
        Captured fresh = new Captured();
        checker("MyPlugin", fresh).check(ID);
        assertEquals(1, fresh.warnings().size());
    }

    @Test
    void cacheIsSharedBetweenPlugins() {
        String shared = "rsbundle";
        String url = SiteClient.releaseUrl(shared);
        http.answer(url, FakeTransport.release(shared, "2.0.0"));
        Captured otherLog = new Captured();
        UpdateChecker other = checker("OtherPlugin", otherLog);

        checker.check(shared);
        other.check(shared);
        assertEquals(1, http.count(url));
        assertEquals("2.0.0", checker.findings().get(shared).version());
        assertEquals("2.0.0", other.findings().get(shared).version());
        assertEquals(List.of("Version 2.0.0 available, installed 1.2.0."), otherLog.messages(Level.INFO));

        now.addAndGet(LATER);
        other.check(shared);
        assertEquals(2, http.count(url));
    }

    @Test
    void productIdsAreValidatedAndTheOwnIdIsThePluginName() {
        assertEquals("myplugin", checker.ownProductId());
        checker.watch("rs_kills");
        checker.checkNow("bad/../path");
        checker.watch("");
        checker.checkNow(null);
        assertEquals(4, log.warnings().size(), log.warnings().toString());
        assertTrue(log.warnings().get(0).contains("'rs_kills' is not a product id"), log.warnings().get(0));

        Captured underscoreLog = new Captured();
        UpdateChecker underscore = checker("My_Plugin", underscoreLog);
        assertEquals("my_plugin", underscore.ownProductId());
        underscore.watch();
        underscore.checkNow();
        assertEquals(2, underscoreLog.warnings().size());

        // Valid ids pass (nothing is scheduled without a context).
        checker.watch();
        checker.watch("RsKills");
        checker.checkNow("rs-kills-2");
        assertEquals(4, log.warnings().size());
        assertTrue(http.sent.isEmpty());
    }

    @Test
    void specDeclaresTheOwnOrAnotherProduct() {
        assertFalse(RsSpec.empty().updates());
        RsSpec own = RsSpec.builder().updates().build();
        assertTrue(own.updates());
        assertNull(own.updatesProduct());
        RsSpec other = RsSpec.builder().updates(" RsKills ").build();
        assertTrue(other.updates());
        assertEquals("rskills", other.updatesProduct());
        for (String bad : List.of("", "rs_kills", "owner/repo", "a".repeat(41))) {
            assertThrows(IllegalArgumentException.class, () -> RsSpec.builder().updates(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> RsSpec.builder().updates(null));
    }

    @Test
    void shutdownStopsChecksAndForgetsFindings() {
        publish("2.0.0");
        checker.check(ID);
        assertEquals(1, checker.findings().size());
        checker.shutdown();
        assertTrue(checker.findings().isEmpty());
        now.addAndGet(LATER);
        checker.check(ID);
        assertEquals(1, http.sent.size());
    }

    @Test
    void permissionAndPlaceholders() {
        assertEquals("myplugin.admin.update", checker.permission());
        Ph[] phs = UpdateChecker.placeholders("MyPlugin", "1.3.0<red>", "1.2.0");
        assertEquals(new Ph("plugin", "MyPlugin", false), phs[0]);
        assertTrue(phs[1].literal(), "remote text must not be rendered");
        assertTrue(phs[2].literal());
    }
}
