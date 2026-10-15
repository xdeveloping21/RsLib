package com.rs.lib.update.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.LongSupplier;

import com.rs.lib.update.Versions;

/**
 * The release list of a repository, page by page ({@code per_page=100}, at most
 * {@value #MAX_PAGES} pages, stopping at the first page with fewer than 100 entries).
 *
 * <p>Each page is cached for five minutes, keyed by repository, page and the SHA-256 of the
 * token (the token itself is never stored), so plugins that share one repository share the
 * requests. Concurrent requests for the same page wait for the one already in flight. Only the
 * (tag, url) pairs are kept, and failures are not cached.</p>
 */
public final class ReleaseFeed {

    static final int PER_PAGE = 100;
    static final int MAX_PAGES = 10;
    static final long TTL_NANOS = 5L * 60L * 1_000_000_000L;

    private final LongSupplier clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<GitHubClient.Page>> inFlight = new ConcurrentHashMap<>();

    /** @param clock nanoseconds, like {@link System#nanoTime()} */
    public ReleaseFeed(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Every release found, newest first as GitHub lists them.
     *
     * @param releases the releases read
     * @param capped   true when the scan stopped at the page cap (there may be older releases)
     */
    public record Scan(List<Release> releases, boolean capped) {
    }

    /**
     * The highest version among the tags that start with {@code prefix}.
     *
     * @param version the version, without the prefix and one leading {@code v}
     * @param release the release that names it
     */
    public record Pick(String version, Release release) {
    }

    private record Cached(GitHubClient.Page page, long at) {
    }

    /** Reads the release list of {@code repo}, through the cache. */
    public Scan scan(GitHubClient github, String repo, String token) throws IOException, InterruptedException {
        List<Release> all = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            GitHubClient.Page got = page(github, repo, page, token);
            all.addAll(got.releases());
            if (got.entries() < PER_PAGE) {
                return new Scan(List.copyOf(all), false);
            }
        }
        return new Scan(List.copyOf(all), true);
    }

    /** The highest valid version among the tags starting with {@code prefix}, or null. */
    public static Pick highest(List<Release> releases, String prefix) {
        return highest(releases, prefix, -1);
    }

    /**
     * Same as {@link #highest(List, String)} limited to versions whose major number is
     * {@code major}; a negative {@code major} accepts every version.
     */
    public static Pick highest(List<Release> releases, String prefix, int major) {
        Pick best = null;
        for (Release release : releases) {
            String version = Versions.fromTag(release.tag(), prefix);
            if (version != null && (major < 0 || Versions.major(version) == major)
                    && (best == null || Versions.compare(version, best.version()) > 0)) {
                best = new Pick(version, release);
            }
        }
        return best;
    }

    /** Forgets every cached page (when RsLib is disabled). */
    public void clear() {
        cache.clear();
    }

    /** The cache key of a page: never the token itself, only its SHA-256. */
    static String key(String repo, int page, String token) {
        String secret = token == null ? "" : token.trim();
        return repo.toLowerCase(Locale.ROOT) + "#" + page + "#"
                + HexFormat.of().formatHex(sha256(secret.getBytes(StandardCharsets.UTF_8)));
    }

    /** The keys cached now (for tests). */
    List<String> cachedKeys() {
        return new ArrayList<>(cache.keySet());
    }

    private GitHubClient.Page page(GitHubClient github, String repo, int page, String token)
            throws IOException, InterruptedException {
        String key = key(repo, page, token);
        Cached cached = cache.get(key);
        if (cached != null && clock.getAsLong() - cached.at() < TTL_NANOS) {
            return cached.page();
        }
        CompletableFuture<GitHubClient.Page> mine = new CompletableFuture<>();
        CompletableFuture<GitHubClient.Page> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            return await(running);
        }
        try {
            GitHubClient.Page fetched = github.releases(repo, page, PER_PAGE, token);
            cache.put(key, new Cached(fetched, clock.getAsLong()));
            mine.complete(fetched);
            return fetched;
        } catch (IOException | InterruptedException | RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private static GitHubClient.Page await(CompletableFuture<GitHubClient.Page> running)
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

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from the JDK", e);
        }
    }
}
