package com.rs.lib.update.internal;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.LongSupplier;

/**
 * What rsdevelopment.dev said about each product, kept for five minutes (the site's own
 * {@code max-age}), so plugins that watch the same product, and RsLib's own updater, share one
 * request. Concurrent requests for the same product wait for the one already in flight;
 * failures are not cached.
 */
public final class ReleaseCache {

    static final long TTL_NANOS = 5L * 60L * 1_000_000_000L;

    private final LongSupplier clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<SiteClient.Info>> inFlight = new ConcurrentHashMap<>();

    private record Cached(SiteClient.Info info, long at) {
    }

    /** @param clock nanoseconds, like {@link System#nanoTime()} */
    public ReleaseCache(LongSupplier clock) {
        this.clock = clock;
    }

    /** The current jar of product {@code id} (a valid id), through the cache. */
    public SiteClient.Info get(SiteClient site, String id) throws IOException, InterruptedException {
        Cached cached = cache.get(id);
        if (cached != null && clock.getAsLong() - cached.at() < TTL_NANOS) {
            return cached.info();
        }
        CompletableFuture<SiteClient.Info> mine = new CompletableFuture<>();
        CompletableFuture<SiteClient.Info> running = inFlight.putIfAbsent(id, mine);
        if (running != null) {
            return await(running);
        }
        try {
            SiteClient.Info fetched = site.release(id);
            cache.put(id, new Cached(fetched, clock.getAsLong()));
            mine.complete(fetched);
            return fetched;
        } catch (IOException | InterruptedException | RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(id, mine);
        }
    }

    /** Forgets everything (when RsLib is disabled). */
    public void clear() {
        cache.clear();
    }

    private static SiteClient.Info await(CompletableFuture<SiteClient.Info> running)
            throws IOException, InterruptedException {
        try {
            return running.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw new IOException(io.getMessage(), io);
            }
            if (cause instanceof InterruptedException) {
                throw new IOException("the shared request was interrupted");
            }
            throw new IOException(cause == null ? "the shared request failed" : cause.toString(), cause);
        }
    }
}
