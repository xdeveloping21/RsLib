package com.rs.lib.update.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/** {@link ReleaseFeed}: pagination, the page cap, the cache and its key, prefix picks. */
class ReleaseFeedTest {

    private static final String REPO = "owner/shared";

    private final AtomicLong now = new AtomicLong(1_000L);
    private final ReleaseFeed feed = new ReleaseFeed(now::get);
    private final FakeTransport http = new FakeTransport();
    private final GitHubClient github = new GitHubClient(http);

    private static List<String> tags(String prefix, int from, int count) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < from + count; i++) {
            out.add(prefix + "1.0." + i);
        }
        return out;
    }

    @Test
    void stopsAtTheFirstShortPage() throws Exception {
        http.answer(FakeTransport.pageUrl(REPO, 1), FakeTransport.releaseList(tags("a-", 0, 100)));
        http.answer(FakeTransport.pageUrl(REPO, 2), FakeTransport.releaseList(tags("a-", 100, 3)));
        ReleaseFeed.Scan scan = feed.scan(github, REPO, null);
        assertEquals(103, scan.releases().size());
        assertFalse(scan.capped());
        assertEquals(2, http.sent.size());
        assertEquals(0, http.count(FakeTransport.pageUrl(REPO, 3)));
    }

    @Test
    void stopsAtThePageCap() throws Exception {
        for (int page = 1; page <= ReleaseFeed.MAX_PAGES + 1; page++) {
            http.answer(FakeTransport.pageUrl(REPO, page), FakeTransport.releaseList(tags("a-", page * 100, 100)));
        }
        ReleaseFeed.Scan scan = feed.scan(github, REPO, null);
        assertTrue(scan.capped());
        assertEquals(ReleaseFeed.MAX_PAGES * 100, scan.releases().size());
        assertEquals(ReleaseFeed.MAX_PAGES, http.sent.size());
        assertEquals(0, http.count(FakeTransport.pageUrl(REPO, ReleaseFeed.MAX_PAGES + 1)));
    }

    @Test
    void entriesWithoutTagStillCountForPagination() throws Exception {
        StringBuilder page = new StringBuilder("[");
        for (int i = 0; i < 100; i++) {
            page.append(i == 0 ? "" : ",").append(i == 5 ? "{\"draft\":true}" : "{\"tag_name\":\"a-1.0." + i + "\"}");
        }
        http.answer(FakeTransport.pageUrl(REPO, 1), page.append(']').toString());
        http.answer(FakeTransport.pageUrl(REPO, 2), "[]");
        ReleaseFeed.Scan scan = feed.scan(github, REPO, null);
        assertEquals(99, scan.releases().size());
        assertEquals(2, http.sent.size());
    }

    @Test
    void pagesAreCachedForFiveMinutes() throws Exception {
        http.answer(FakeTransport.pageUrl(REPO, 1), FakeTransport.releaseList(List.of("a-1.0.0")));
        feed.scan(github, REPO, null);
        feed.scan(github, "OWNER/Shared", null);
        assertEquals(1, http.sent.size());
        now.addAndGet(ReleaseFeed.TTL_NANOS);
        feed.scan(github, REPO, null);
        assertEquals(2, http.sent.size());
        feed.clear();
        feed.scan(github, REPO, null);
        assertEquals(3, http.sent.size());
    }

    @Test
    void failuresAreNotCached() throws Exception {
        http.answer(FakeTransport.pageUrl(REPO, 1), 502, "bad gateway");
        IOException failure = assertThrows(IOException.class, () -> feed.scan(github, REPO, null));
        assertTrue(failure.getMessage().contains("HTTP 502"), failure.getMessage());
        http.answer(FakeTransport.pageUrl(REPO, 1), FakeTransport.releaseList(List.of("a-1.0.0")));
        assertEquals(1, feed.scan(github, REPO, null).releases().size());
        assertEquals(2, http.sent.size());
    }

    @Test
    void cacheKeyNeverHoldsTheToken() throws Exception {
        String token = "ghp_TopSecretValue123";
        String key = ReleaseFeed.key(REPO, 1, token);
        assertFalse(key.contains(token));
        assertFalse(key.contains("TopSecret"));
        assertTrue(key.matches("owner/shared#1#[0-9a-f]{64}"), key);
        assertNotEquals(key, ReleaseFeed.key(REPO, 1, "other"));
        assertNotEquals(key, ReleaseFeed.key(REPO, 1, null));
        assertEquals(ReleaseFeed.key(REPO, 1, null), ReleaseFeed.key(REPO, 1, ""));

        http.answer(FakeTransport.pageUrl(REPO, 1), FakeTransport.releaseList(List.of("a-1.0.0")));
        feed.scan(github, REPO, token);
        assertEquals(List.of(key), feed.cachedKeys());
        // Another token is another cache entry.
        feed.scan(github, REPO, "other");
        assertEquals(2, http.sent.size());
    }

    @Test
    void concurrentRequestsShareOneFetch() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        HttpTransport slow = new HttpTransport() {
            @Override
            public Response get(String url, Map<String, String> headers) throws InterruptedException {
                calls.incrementAndGet();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return new Response(200, FakeTransport.releaseList(List.of("a-1.0.0")));
            }

            @Override
            public long download(String url, Map<String, String> headers, Path target, long maxBytes) {
                throw new UnsupportedOperationException();
            }
        };
        // Every lookup sees an expired cache, so only the in-flight request can be shared.
        AtomicLong clock = new AtomicLong();
        ReleaseFeed uncached = new ReleaseFeed(() -> clock.getAndAdd(ReleaseFeed.TTL_NANOS));
        GitHubClient client = new GitHubClient(slow);
        AtomicReference<ReleaseFeed.Scan> first = new AtomicReference<>();
        AtomicReference<ReleaseFeed.Scan> second = new AtomicReference<>();
        Thread a = new Thread(() -> first.set(scanQuietly(uncached, client)));
        a.start();
        while (calls.get() == 0) {
            Thread.onSpinWait();
        }
        Thread b = new Thread(() -> second.set(scanQuietly(uncached, client)));
        b.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (b.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        release.countDown();
        a.join(10_000);
        b.join(10_000);
        assertEquals(1, calls.get());
        assertEquals(first.get(), second.get());
    }

    private static ReleaseFeed.Scan scanQuietly(ReleaseFeed feed, GitHubClient client) {
        try {
            return feed.scan(client, REPO, null);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void highestKeepsThePrefixStripsOneVAndPicksTheTopVersion() {
        List<Release> releases = List.of(
                new Release("rskills-1.2.0", "u1"),
                new Release("rskills-v1.10.0", "u2"),
                new Release("rskills-2.0.0-beta", "u3"),
                new Release("rsother-9.0.0", "u4"),
                new Release("rskills-nightly", "u5"),
                new Release("rskills-vv3.0.0", "u6"),
                new Release("1.9.0", "u7"));
        ReleaseFeed.Pick pick = ReleaseFeed.highest(releases, "rskills-");
        assertEquals("2.0.0-beta", pick.version());
        assertEquals("u3", pick.release().url());

        ReleaseFeed.Pick released = ReleaseFeed.highest(releases.subList(0, 2), "rskills-");
        assertEquals("1.10.0", released.version());
        assertNull(ReleaseFeed.highest(releases, "rsnothing-"));
    }
}
