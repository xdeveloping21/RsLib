package com.rs.lib.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class PlayerLookupTest {

    private static final String NOTCH_ID = "069a79f444e94726a5befca90e38aaf5";
    private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    /** Answers from {@code answer}, recording each URL. */
    private static final class FakeHttp implements Function<String, CompletableFuture<PlayerLookup.Response>> {

        final List<String> urls = new CopyOnWriteArrayList<>();
        volatile CompletableFuture<PlayerLookup.Response> next;

        @Override
        public CompletableFuture<PlayerLookup.Response> apply(String url) {
            urls.add(url);
            return next;
        }

        FakeHttp answer(int status, String body) {
            next = CompletableFuture.completedFuture(new PlayerLookup.Response(status, body));
            return this;
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    @Test
    void readsTheIdAndPutsTheDashesBack() throws Exception {
        FakeHttp http = new FakeHttp().answer(200, "{\"id\":\"" + NOTCH_ID + "\",\"name\":\"Notch\"}");
        PlayerLookup.Client client = new PlayerLookup.Client(http);
        assertEquals(Optional.of(NOTCH), await(client.fetchUuid("Notch")));
        assertEquals(List.of(PlayerLookup.URL + "Notch"), http.urls);
        assertEquals(Optional.of(NOTCH), await(client.fetchUuid("NOTCH")), "case-insensitive cache");
        assertEquals(1, http.urls.size());
        assertEquals(NOTCH, PlayerLookup.Client.dashed(NOTCH_ID.toUpperCase()));
        assertNull(PlayerLookup.Client.dashed("069a79f4-44e9-4726-a5be-fca90e38aaf5"));
        assertNull(PlayerLookup.Client.dashed("zz9a79f444e94726a5befca90e38aaf5"));
    }

    @Test
    void invalidNamesNeverReachTheNetwork() throws Exception {
        FakeHttp http = new FakeHttp().answer(200, "{}");
        PlayerLookup.Client client = new PlayerLookup.Client(http);
        for (String name : new String[] {null, "", "has space", "seventeen_chars_x", "ñandú", "a/b"}) {
            assertEquals(Optional.empty(), await(client.fetchUuid(name)));
        }
        assertTrue(http.urls.isEmpty());
    }

    @Test
    void unknownPlayersAreCachedButErrorsAreNot() throws Exception {
        FakeHttp http = new FakeHttp().answer(204, "");
        PlayerLookup.Client client = new PlayerLookup.Client(http);
        assertEquals(Optional.empty(), await(client.fetchUuid("Nobody")));
        http.answer(404, "{\"errorMessage\":\"Couldn't find any profile with name Ghost\"}");
        assertEquals(Optional.empty(), await(client.fetchUuid("Ghost")));
        assertEquals(Optional.empty(), await(client.fetchUuid("nobody")));
        assertEquals(2, http.urls.size());

        http.answer(429, "slow down");
        ExecutionException limited = assertThrows(ExecutionException.class, () -> await(client.fetchUuid("Busy")));
        assertInstanceOf(IOException.class, limited.getCause());
        http.answer(200, "not json");
        assertInstanceOf(IOException.class,
                assertThrows(ExecutionException.class, () -> await(client.fetchUuid("Busy"))).getCause());
        http.next = CompletableFuture.failedFuture(new IOException("timed out"));
        assertEquals("timed out",
                assertThrows(ExecutionException.class, () -> await(client.fetchUuid("Busy"))).getCause().getMessage());
        http.answer(200, "{\"id\":\"" + NOTCH_ID + "\"}");
        assertEquals(Optional.of(NOTCH), await(client.fetchUuid("Busy")));
        assertEquals(6, http.urls.size());
        client.clearCache();
        await(client.fetchUuid("Busy"));
        assertEquals(7, http.urls.size());
    }

    @Test
    void simultaneousLookupsShareOneRequest() throws Exception {
        FakeHttp http = new FakeHttp();
        CompletableFuture<PlayerLookup.Response> pending = new CompletableFuture<>();
        http.next = pending;
        PlayerLookup.Client client = new PlayerLookup.Client(http);
        CompletableFuture<Optional<UUID>> first = client.fetchUuid("Notch");
        CompletableFuture<Optional<UUID>> second = client.fetchUuid("notch");
        assertNotSame(first, second);
        assertEquals(1, http.urls.size());
        second.cancel(false);
        pending.complete(new PlayerLookup.Response(200, "{\"id\":\"" + NOTCH_ID + "\"}"));
        assertEquals(Optional.of(NOTCH), await(first), "cancelling one caller's copy leaves the others alone");
    }

    @Test
    void answersCompleteOffTheCallingThread() throws Exception {
        FakeHttp http = new FakeHttp();
        CompletableFuture<PlayerLookup.Response> pending = new CompletableFuture<>();
        http.next = pending;
        PlayerLookup.Client client = new PlayerLookup.Client(http);
        CompletableFuture<Thread> completer = client.fetchUuid("Notch").thenApply(id -> Thread.currentThread());
        // The HTTP answer arrives on this thread; the lookup must still finish elsewhere.
        pending.complete(new PlayerLookup.Response(200, "{\"id\":\"" + NOTCH_ID + "\"}"));
        assertNotSame(Thread.currentThread(), await(completer));
    }
}
