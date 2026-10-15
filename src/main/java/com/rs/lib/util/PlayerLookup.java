package com.rs.lib.util;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Asks Mojang which UUID a player name belongs to, without ever blocking the caller.
 *
 * <p>Calls {@code https://api.mojang.com/users/profiles/minecraft/<name>} with
 * {@code User-Agent: RsLib-PlayerLookup}. A name that is not 1 to 16 letters, digits or
 * underscores is answered "unknown" without a request. Answers, "no such player" included
 * (HTTP 204 or 404), are kept for the last {@value #CACHE_SIZE} names; other statuses, timeouts
 * and network errors fail the future with an {@link IOException} and are not kept, so the next
 * call asks again. Simultaneous lookups of one name (in any case) share a single request. A
 * request's future completes on the HTTP client's threads, never on the server's main thread;
 * a cached answer is returned already complete.</p>
 */
public final class PlayerLookup {

    static final String URL = "https://api.mojang.com/users/profiles/minecraft/";
    static final String USER_AGENT = "RsLib-PlayerLookup";
    private static final int CACHE_SIZE = 512;
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private static final Client DEFAULT = new Client(Client::send);

    private PlayerLookup() {
    }

    /** The UUID of the player called {@code name}, or empty when no such player exists. */
    public static CompletableFuture<Optional<UUID>> fetchUuid(String name) {
        return DEFAULT.fetchUuid(name);
    }

    /** Forgets every cached answer. */
    public static void clearCache() {
        DEFAULT.clearCache();
    }

    /** A status code and its body. */
    record Response(int status, String body) {
    }

    /** The lookup itself, over any way of sending a GET (tests use a fake). */
    static final class Client {

        private final Function<String, CompletableFuture<Response>> http;
        private final Map<String, Optional<UUID>> cache = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Optional<UUID>> eldest) {
                return size() > CACHE_SIZE;
            }
        };
        private final Map<String, CompletableFuture<Optional<UUID>>> inFlight = new ConcurrentHashMap<>();

        /** @param http sends a GET to a URL and completes with the answer, off the main thread */
        Client(Function<String, CompletableFuture<Response>> http) {
            this.http = Objects.requireNonNull(http, "http");
        }

        CompletableFuture<Optional<UUID>> fetchUuid(String name) {
            if (name == null || !VALID_NAME.matcher(name).matches()) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            String key = name.toLowerCase(Locale.ROOT);
            Optional<UUID> cached = cached(key);
            if (cached != null) {
                return CompletableFuture.completedFuture(cached);
            }
            CompletableFuture<Optional<UUID>> mine = new CompletableFuture<>();
            CompletableFuture<Optional<UUID>> running = inFlight.putIfAbsent(key, mine);
            if (running != null) {
                return running.copy();
            }
            // An answer may have landed between the cache check and the claim.
            cached = cached(key);
            if (cached != null) {
                inFlight.remove(key, mine);
                mine.complete(cached);
                return mine.copy();
            }
            CompletableFuture<Response> sent;
            try {
                sent = http.apply(URL + name);
            } catch (RuntimeException e) {
                sent = CompletableFuture.failedFuture(e);
            }
            // Always handled on a pool thread, so a request never completes on the caller's thread.
            sent.whenCompleteAsync((response, error) -> {
                Optional<UUID> answer = null;
                Throwable failure = error;
                if (failure == null) {
                    try {
                        answer = read(response);
                    } catch (IOException e) {
                        failure = e;
                    }
                }
                if (answer != null) {
                    synchronized (cache) {
                        cache.put(key, answer);
                    }
                }
                inFlight.remove(key, mine);
                if (answer != null) {
                    mine.complete(answer);
                } else {
                    mine.completeExceptionally(asIo(failure, name));
                }
            });
            return mine.copy();
        }

        void clearCache() {
            synchronized (cache) {
                cache.clear();
            }
        }

        private Optional<UUID> cached(String key) {
            synchronized (cache) {
                return cache.get(key);
            }
        }

        /** The answer in {@code response}; an {@link IOException} for anything that may be transient. */
        static Optional<UUID> read(Response response) throws IOException {
            int status = response.status();
            if (status == 204 || status == 404) {
                return Optional.empty();
            }
            if (status != 200) {
                throw new IOException("Mojang answered HTTP " + status);
            }
            try {
                JsonElement root = JsonParser.parseString(response.body() == null ? "" : response.body());
                JsonElement id = root.isJsonObject() ? root.getAsJsonObject().get("id") : null;
                UUID uuid = id != null && id.isJsonPrimitive() ? dashed(id.getAsString()) : null;
                if (uuid == null) {
                    throw new IOException("Mojang's answer has no valid id");
                }
                return Optional.of(uuid);
            } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
                throw new IOException("Mojang's answer is not valid JSON", e);
            }
        }

        /** A 32-digit hex id with the dashes put back, or null. */
        static UUID dashed(String hex) {
            if (hex == null || hex.length() != 32) {
                return null;
            }
            for (int i = 0; i < 32; i++) {
                if (Character.digit(hex.charAt(i), 16) < 0) {
                    return null;
                }
            }
            return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16),
                    Long.parseUnsignedLong(hex.substring(16), 16));
        }

        private static IOException asIo(Throwable failure, String name) {
            Throwable cause = failure;
            while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                    && cause.getCause() != null) {
                cause = cause.getCause();
            }
            return cause instanceof IOException io ? io
                    : new IOException("Looking up " + name + " failed: " + cause, cause);
        }

        /** The real request, on a shared JDK client. */
        private static CompletableFuture<Response> send(String url) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            return Http.CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> new Response(response.statusCode(), response.body()));
        }
    }

    /** The JDK client, created on the first real request. */
    private static final class Http {

        static final HttpClient CLIENT = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
