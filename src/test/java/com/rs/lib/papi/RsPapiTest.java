package com.rs.lib.papi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

class RsPapiTest {

    private final FakePapiHost host = new FakePapiHost();
    private final RsPapi papi = new RsPapi(host);

    // ------------------------------------------------------------------ resolving

    @Test
    void offTheMainThreadTokensStayAsWritten() {
        host.primary = false;
        String text = "Server: %server_name%";
        assertSame(text, papi.apply(null, text));
        List<String> lines = List.of("%server_name%");
        assertSame(lines, papi.apply(null, lines));
        assertTrue(host.resolved.isEmpty());
        assertEquals(2, host.traces.size());
        assertTrue(host.traces.get(0).contains(text));
    }

    @Test
    void applyHereResolvesOnAnyThread() {
        host.primary = false;
        assertEquals("Server: Lobby", papi.applyHere(null, "Server: %server_name%"));
        assertEquals(List.of("Lobby", "x"), papi.applyHere(null, List.of("%server_name%", "x")));
    }

    @Test
    void onTheMainThreadTokensResolve() {
        assertEquals("Server: Lobby", papi.apply(null, "Server: %server_name%"));
        assertEquals(List.of("Lobby"), papi.apply(null, List.of("%server_name%")));
    }

    @Test
    void textWithoutPercentIsNotTouched() {
        String text = "plain";
        assertSame(text, papi.apply(null, text));
        assertSame(text, papi.applyHere(null, text));
        assertNull(papi.apply(null, (String) null));
        assertNull(papi.apply(null, (List<String>) null));
        assertNull(papi.applyHere(null, (List<String>) null));
        assertTrue(host.resolved.isEmpty());
        assertEquals(0, host.lookups);
    }

    @Test
    void withoutPlaceholderApiTextIsUnchanged() {
        host.enabled = false;
        assertFalse(papi.available());
        String text = "%server_name%";
        assertSame(text, papi.apply(null, text));
        assertTrue(host.resolved.isEmpty());
    }

    @Test
    void aFailingExpansionGivesTheTextBack() {
        host.resolver = t -> {
            throw new IllegalStateException("expansion bug");
        };
        String text = "%broken_x%";
        assertSame(text, papi.applyHere(null, text));
        assertTrue(papi.available());
    }

    @Test
    void anIncompatiblePlaceholderApiTurnsTheModuleOffUntilInvalidated() {
        host.resolver = t -> {
            throw new NoSuchMethodError("setPlaceholders");
        };
        String text = "%server_name%";
        assertSame(text, papi.apply(null, text));
        assertFalse(papi.available());
        assertSame(text, papi.apply(null, text));
        assertEquals(1, host.resolved.size());
        assertEquals(1, host.log.warnings().size());
        host.resolver = t -> "ok";
        papi.invalidate();
        assertTrue(papi.available());
        assertEquals("ok", papi.apply(null, text));
    }

    // ------------------------------------------------------------------ applyOnMain

    @Test
    void applyOnMainResolvesAtOnceOnTheMainThread() {
        CompletableFuture<String> future = papi.applyOnMain(null, "%server_name%");
        assertTrue(future.isDone());
        assertEquals("Lobby", future.join());
    }

    @Test
    void applyOnMainHopsToTheMainThread() {
        host.primary = false;
        CompletableFuture<String> future = papi.applyOnMain(null, "%server_name%");
        CompletableFuture<List<String>> lines = papi.applyOnMain(null, List.of("%server_name%", "b"));
        assertFalse(future.isDone());
        host.primary = true;
        assertEquals(2, host.runTasks());
        assertEquals("Lobby", future.join());
        assertEquals(List.of("Lobby", "b"), lines.join());
    }

    @Test
    void applyOnMainFailsOpen() {
        host.primary = false;
        host.disabledPlugin = true;
        assertEquals("%server_name%", papi.applyOnMain(null, "%server_name%").join());

        host.disabledPlugin = false;
        CompletableFuture<String> pending = papi.applyOnMain(null, "%server_name%");
        papi.shutdown();
        assertEquals("%server_name%", pending.join());
        host.runTasks();
        assertEquals("%server_name%", pending.join());
    }

    @Test
    void applyOnMainWithoutTokensNeedsNoHop() {
        host.primary = false;
        CompletableFuture<String> future = papi.applyOnMain(null, "plain");
        assertTrue(future.isDone());
        assertTrue(host.tasks.isEmpty());
    }

    // ------------------------------------------------------------------ expansions

    @Test
    void registerReplacesThePreviousExpansionWithTheSameIdentifier() {
        assertTrue(papi.expansion("RsKills").global("a", () -> "1").register());
        PapiHolder first = host.papi.get("rskills");
        assertTrue(papi.expansion("rskills").global("a", () -> "2").register());
        PapiHolder second = host.papi.get("rskills");
        assertTrue(first != second);
        assertEquals("2", second.resolve(null, "a"));
        papi.unregisterAll();
        assertTrue(host.papi.isEmpty());
        assertEquals(List.of("rskills"), host.unregistered);
    }

    @Test
    void unregisterAllLeavesAnotherPluginsExpansionAlone() {
        papi.expansion("shared").global("a", () -> "1").register();
        RsPapi other = new RsPapi(host);
        other.expansion("shared").global("a", () -> "2").register();
        papi.unregisterAll();
        assertEquals("2", host.papi.get("shared").resolve(null, "a"));
    }

    @Test
    void anExpansionDeclaredBeforePlaceholderApiIsRegisteredWhenItArrives() {
        host.enabled = false;
        assertFalse(papi.expansion("rs").global("a", () -> "1").register());
        assertTrue(host.papi.isEmpty());
        host.enabled = true;
        papi.invalidate();
        papi.invalidate();
        assertEquals(1, host.runTasks());
        assertEquals("1", host.papi.get("rs").resolve(null, "a"));
    }

    @Test
    void anExpansionLostByPlaceholderApiIsRegisteredAgain() {
        papi.expansion("rs").global("a", () -> "1").register();
        host.papi.clear();
        papi.invalidate();
        host.runTasks();
        assertTrue(host.papi.containsKey("rs"));
        papi.invalidate();
        host.runTasks();
        assertEquals(1, host.papi.size());
    }

    @Test
    void registerOffTheMainThreadWaitsForIt() {
        host.primary = false;
        assertFalse(papi.expansion("rs").global("a", () -> "1").register());
        assertTrue(host.papi.isEmpty());
        host.primary = true;
        host.runTasks();
        assertTrue(host.papi.containsKey("rs"));
    }

    @Test
    void aRefusedExpansionIsReported() {
        host.refuse = true;
        assertFalse(papi.expansion("rs").register());
        assertTrue(host.log.warnings().get(0).contains("refused"));
    }

    @Test
    void aPlaceholderApiThatCannotRegisterStillResolves() {
        host.registerError = new NoSuchMethodError("getExpansion");
        assertFalse(papi.expansion("rs").register());
        assertEquals(1, host.log.warnings().size());
        assertTrue(papi.available());
        assertEquals("Lobby", papi.apply(null, "%server_name%"));
    }

    @Test
    void nothingIsRegisteredAgainAfterUnregisterAll() {
        host.enabled = false;
        papi.expansion("rs").global("a", () -> "1").register();
        papi.unregisterAll();
        host.enabled = true;
        papi.invalidate();
        host.runTasks();
        assertTrue(host.papi.isEmpty());
    }

    @Test
    void aRetryQueuedBeforeShutdownDoesNothing() {
        host.enabled = false;
        papi.expansion("rs").register();
        host.enabled = true;
        papi.invalidate();
        papi.shutdown();
        host.runTasks();
        assertTrue(host.papi.isEmpty());
    }
}
