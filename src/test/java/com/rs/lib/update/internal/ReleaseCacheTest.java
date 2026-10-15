package com.rs.lib.update.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/** {@link ReleaseCache}: five minutes per product, failures not kept, one request in flight. */
class ReleaseCacheTest {

    private static final String URL = SiteClient.releaseUrl("rskills");

    private final AtomicLong now = new AtomicLong(1_000L);
    private final ReleaseCache cache = new ReleaseCache(now::get);
    private final FakeTransport http = new FakeTransport();
    private final SiteClient site = new SiteClient(http, "RsLib/test");

    @Test
    void answersAreKeptForFiveMinutesPerProduct() throws Exception {
        http.answer(URL, FakeTransport.release("rskills", "1.0.0"));
        http.answer(SiteClient.releaseUrl("rsother"), FakeTransport.release("rsother", "3.0.0"));
        assertEquals("1.0.0", cache.get(site, "rskills").version());
        http.answer(URL, FakeTransport.release("rskills", "1.1.0"));
        assertEquals("1.0.0", cache.get(site, "rskills").version());
        assertEquals("3.0.0", cache.get(site, "rsother").version());
        assertEquals(2, http.sent.size());

        now.addAndGet(ReleaseCache.TTL_NANOS);
        assertEquals("1.1.0", cache.get(site, "rskills").version());
        assertEquals(3, http.sent.size());
        cache.clear();
        cache.get(site, "rskills");
        assertEquals(4, http.sent.size());
    }

    @Test
    void failuresAreNotCached() throws Exception {
        http.answer(URL, 502, "bad gateway");
        IOException failure = assertThrows(IOException.class, () -> cache.get(site, "rskills"));
        assertTrue(failure.getMessage().contains("HTTP 502"), failure.getMessage());
        http.answer(URL, FakeTransport.release("rskills", "1.0.0"));
        assertEquals("1.0.0", cache.get(site, "rskills").version());
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
                return new Response(200, FakeTransport.release("rskills", "1.0.0"));
            }

            @Override
            public long download(String url, Map<String, String> headers, Path target, long maxBytes) {
                throw new UnsupportedOperationException();
            }
        };
        // Every lookup sees an expired cache, so only the in-flight request can be shared.
        AtomicLong clock = new AtomicLong();
        ReleaseCache uncached = new ReleaseCache(() -> clock.getAndAdd(ReleaseCache.TTL_NANOS));
        SiteClient client = new SiteClient(slow, "RsLib/test");
        AtomicReference<SiteClient.Info> first = new AtomicReference<>();
        AtomicReference<SiteClient.Info> second = new AtomicReference<>();
        Thread a = new Thread(() -> first.set(getQuietly(uncached, client)));
        a.start();
        while (calls.get() == 0) {
            Thread.onSpinWait();
        }
        Thread b = new Thread(() -> second.set(getQuietly(uncached, client)));
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

    private static SiteClient.Info getQuietly(ReleaseCache cache, SiteClient client) {
        try {
            return cache.get(client, "rskills");
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
