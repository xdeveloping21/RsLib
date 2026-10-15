package com.rs.lib.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;
import com.rs.lib.update.internal.FakeTransport;
import com.rs.lib.update.internal.GitHubClient;
import com.rs.lib.update.internal.ReleaseFeed;
import com.rs.lib.yml.YmlTestSupport.Captured;

/** {@link UpdateChecker} without a server: one check at a time, against fixtures. */
class UpdateCheckerTest {

    private static final String REPO = "owner/myplugin";
    private static final String LATEST = GitHubClient.API_ROOT + "/repos/" + REPO + "/releases/latest";

    private final Captured log = new Captured();
    private final FakeTransport http = new FakeTransport();
    private final AtomicReference<String> token = new AtomicReference<>();
    private final AtomicReference<String> installed = new AtomicReference<>("1.2.0");
    private final UpdateChecker checker = new UpdateChecker(null, "MyPlugin", installed::get, token::get,
            log.logger, new GitHubClient(http), new ReleaseFeed(System::nanoTime));

    private static String latest(String tag) {
        return "{\"tag_name\":\"" + tag + "\",\"html_url\":\"https://github.com/" + REPO + "/releases/tag/" + tag
                + "\",\"assets\":[]}";
    }

    @Test
    void newerReleaseIsReportedOnceUntilItChanges() {
        http.answer(LATEST, latest("v1.3.0"));
        checker.check(REPO, null);
        checker.check(REPO, null);
        assertEquals(List.of("Version 1.3.0 available, installed 1.2.0."), log.messages(Level.INFO));
        UpdateChecker.Finding finding = checker.findings().get("owner/myplugin");
        assertEquals("1.3.0", finding.version());
        assertEquals("https://github.com/" + REPO + "/releases/tag/v1.3.0", finding.url());

        http.answer(LATEST, latest("v1.4.0"));
        checker.check(REPO, null);
        assertEquals(2, log.messages(Level.INFO).size());
        assertEquals("1.4.0", checker.findings().get("owner/myplugin").version());

        installed.set("1.4.0");
        checker.check(REPO, null);
        assertTrue(checker.findings().isEmpty());
        assertEquals(2, log.messages(Level.INFO).size());
    }

    @Test
    void sameOrOlderOrUnparsableIsNotReported() {
        for (String tag : List.of("1.2.0", "v1.1.9", "1.2.0-rc.1", "nightly")) {
            http.answer(LATEST, latest(tag));
            checker.check(REPO, null);
        }
        assertTrue(log.records.isEmpty());
        assertTrue(checker.findings().isEmpty());
    }

    @Test
    void headersAndTokenAreSentButTheTokenIsNeverLogged() {
        http.answer(LATEST, 500, "boom");
        token.set("  ghp_secret123 ");
        checker.check(REPO, null);
        Map<String, String> headers = http.sent.get(0).headers();
        assertEquals("application/vnd.github+json", headers.get("Accept"));
        assertEquals("2022-11-28", headers.get("X-GitHub-Api-Version"));
        assertEquals("RsLib-UpdateChecker", headers.get("User-Agent"));
        assertEquals("Bearer ghp_secret123", headers.get("Authorization"));
        for (LogRecord record : log.records) {
            assertFalse(record.getMessage().contains("ghp_secret123"), record.getMessage());
        }
        // The token is read again on every check: a blank one sends no Authorization.
        token.set(" ");
        checker.check(REPO, null);
        assertFalse(http.sent.get(1).headers().containsKey("Authorization"));
    }

    @Test
    void failuresWarnOncePerRepository() {
        http.answer(LATEST, 503, "unavailable");
        checker.check(REPO, null);
        checker.check(REPO, null);
        http.answer(LATEST, "{\"name\":\"no tag\"}");
        checker.check(REPO, null);
        List<String> warnings = log.warnings();
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("HTTP 503"), warnings.get(0));

        checker.check("owner/other", null);
        assertEquals(2, log.warnings().size());
    }

    @Test
    void tagPrefixPicksTheHighestOwnTag() {
        String shared = "owner/shared";
        http.answer(FakeTransport.pageUrl(shared, 1), FakeTransport.releaseList(
                List.of("rsother-9.0.0", "rskills-v1.10.0", "rskills-1.9.0", "rskills-2.0.0-beta", "rskills-x")));
        checker.check(shared, "rskills-");
        assertEquals("2.0.0-beta", checker.findings().get("owner/shared").version());
        assertEquals(List.of("Version 2.0.0-beta available, installed 1.2.0."), log.messages(Level.INFO));
        assertFalse(http.sent.get(0).url().contains("latest"));
    }

    @Test
    void tagPrefixWithoutMatchesWarnsAndSaysWhenCapped() {
        String shared = "owner/shared";
        http.answer(FakeTransport.pageUrl(shared, 1), FakeTransport.releaseList(List.of("rsother-1.0.0")));
        checker.check(shared, "rskills-");
        assertTrue(log.warnings().get(0).contains("'rskills-'"), log.warnings().toString());
        assertFalse(log.warnings().get(0).contains("page limit"));

        StringBuilder full = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            full.append(i == 0 ? "" : ",").append("{\"tag_name\":\"rsother-1.0.").append(i).append("\"}");
        }
        for (int page = 1; page <= 10; page++) {
            http.answer(FakeTransport.pageUrl("owner/big", page), "[" + full + "]");
        }
        checker.check("owner/big", "rskills-");
        assertTrue(log.warnings().get(1).contains("page limit"), log.warnings().toString());
    }

    @Test
    void invalidRepositoriesWarnAndDoNothing() {
        for (String repo : List.of("noslash", "a/b/c", "own er/repo", "owner/..", "../x", "a/b?x=1")) {
            assertFalse(UpdateChecker.isValidRepo(repo), repo);
        }
        assertTrue(UpdateChecker.isValidRepo("Some_Owner/my-repo.java"));
        checker.watch("not a repo");
        checker.checkNow("bad/../path");
        assertEquals(2, log.warnings().size());
        assertTrue(http.sent.isEmpty());
    }

    @Test
    void shutdownStopsChecksAndForgetsFindings() {
        http.answer(LATEST, latest("2.0.0"));
        checker.check(REPO, null);
        assertEquals(1, checker.findings().size());
        checker.shutdown();
        assertTrue(checker.findings().isEmpty());
        checker.check(REPO, null);
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
