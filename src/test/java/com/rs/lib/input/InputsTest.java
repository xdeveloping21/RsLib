package com.rs.lib.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Chat questions and the notes' traps, without a server. */
class InputsTest {

    private FakeInputHost host;
    private Inputs inputs;
    private final boolean[] online = {true};
    private Player alex;

    @BeforeEach
    void setUp() {
        host = new FakeInputHost();
        inputs = new Inputs(host);
        alex = FakeInputHost.player(online);
    }

    @AfterEach
    void tearDown() {
        Inputs.ACTIVE.clear();
    }

    /** Types {@code text} in chat and lets the next tick run. */
    private void say(String text) {
        assertTrue(Inputs.capture(alex, text), "no open question took '" + text + "'");
        host.tick();
    }

    private static <T> InputResult<T> done(CompletableFuture<InputResult<T>> future) {
        assertTrue(future.isDone(), "the question has not ended");
        return future.join();
    }

    /** Trap 1: the legacy chat event is heard first (LOWEST) and cancelled; the modern one is the fallback. */
    @Test
    @SuppressWarnings("deprecation")
    void chatIsTakenAtLowestAndCancelled() throws Exception {
        Listener listener = Inputs.chatListener();
        Method legacy = listener.getClass().getMethod("onLegacyChat", AsyncPlayerChatEvent.class);
        Method modern = listener.getClass().getMethod("onChat", io.papermc.paper.event.player.AsyncChatEvent.class);
        assertEquals(EventPriority.LOWEST, legacy.getAnnotation(EventHandler.class).priority());
        assertFalse(legacy.getAnnotation(EventHandler.class).ignoreCancelled());
        assertEquals(EventPriority.LOWEST, modern.getAnnotation(EventHandler.class).priority());
        assertTrue(modern.getAnnotation(EventHandler.class).ignoreCancelled(),
                "the modern event arrives cancelled after the legacy one took the answer");
        legacy.setAccessible(true);

        AsyncPlayerChatEvent chatter = new AsyncPlayerChatEvent(true, alex, "hello all", new HashSet<>());
        legacy.invoke(listener, chatter);
        assertFalse(chatter.isCancelled(), "chat without a question must pass");

        CompletableFuture<InputResult<String>> future = inputs.text(alex).open();
        AsyncPlayerChatEvent answer = new AsyncPlayerChatEvent(true, alex, "  my answer ", new HashSet<>());
        legacy.invoke(listener, answer);
        assertTrue(answer.isCancelled(), "the answer must never reach the public chat");
        host.tick();
        assertEquals("my answer", done(future).value());
    }

    /** Trap 2: chat arrives on another thread; the answer is checked on the player's thread. */
    @Test
    void answersAreCheckedOnThePlayersThread() {
        AtomicBoolean parsed = new AtomicBoolean();
        CompletableFuture<InputResult<String>> future = inputs.custom(alex, typed -> {
            parsed.set(true);
            return typed;
        }).open();
        assertTrue(Inputs.capture(alex, "x"));
        assertFalse(parsed.get(), "parsed on the chat thread");
        assertFalse(future.isDone());
        host.tick();
        assertTrue(parsed.get());
        assertEquals(InputStatus.COMPLETED, done(future).status());
    }

    /** Trap 3: one question per player on the whole server; a new one replaces the old. */
    @Test
    void oneQuestionPerPlayerAcrossPlugins() {
        Inputs otherPlugin = new Inputs(host);
        CompletableFuture<InputResult<String>> first = inputs.text(alex).open();
        CompletableFuture<InputResult<Long>> second = otherPlugin.whole(alex, 1, 5).open();
        assertEquals(InputStatus.REPLACED, done(first).status());
        assertNull(done(first).value());
        assertFalse(inputs.isWaiting(alex.getUniqueId()));
        assertTrue(otherPlugin.isWaiting(alex.getUniqueId()));
        inputs.cancel(alex.getUniqueId());
        assertFalse(second.isDone(), "a plugin cannot cancel another plugin's question");
        say("3");
        assertEquals(3L, done(second).value());
        assertTrue(host.timers.stream().allMatch(t -> t.cancelled), "a timer kept running");
    }

    /** Trap 4: without a timer the question could swallow the chat forever: it ends at once. */
    @Test
    void aQuestionWithoutATimerEndsAtOnce() {
        host.refuseTimers = true;
        CompletableFuture<InputResult<String>> future = inputs.text(alex).prompt("ask").open();
        assertEquals(InputStatus.SHUT_DOWN, done(future).status());
        assertFalse(Inputs.capture(alex, "chat"), "the ended question still takes chat");
        assertFalse(host.sent.contains("ask"), "asked a question that cannot end");
    }

    /** Trap 5: a SHUT_DOWN callback cannot open a new question. */
    @Test
    void noNewQuestionFromAShutDownCallback() {
        AtomicReference<CompletableFuture<InputResult<String>>> reopened = new AtomicReference<>();
        CompletableFuture<InputResult<String>> future = inputs.text(alex).open();
        future.thenAccept(result -> reopened.set(inputs.text(alex).open()));
        inputs.shutdown();
        assertEquals(InputStatus.SHUT_DOWN, done(future).status());
        assertEquals(InputStatus.SHUT_DOWN, done(reopened.get()).status());
        assertFalse(Inputs.capture(alex, "chat"));
        host.shuttingDown = true;
        assertEquals(InputStatus.SHUT_DOWN, done(new Inputs(host).text(alex).open()).status());
    }

    @Test
    void promptHintAndCancelWords() {
        CompletableFuture<InputResult<String>> future = inputs.text(alex).prompt("clan.ask-name").open();
        assertEquals(List.of("clan.ask-name", "rslib.input.cancel-hint words=cancel, cancelar"), host.sent);
        say("CANCELAR");
        assertEquals(InputStatus.CANCELLED, done(future).status());
        assertEquals("rslib.input.cancelled", host.sent.get(host.sent.size() - 1));

        host.sent.clear();
        CompletableFuture<InputResult<Long>> number = inputs.whole(alex, 1, 5).promptText("&eHow many?")
                .cancelWords("stop").silent(true).open();
        assertEquals(List.of("text:&eHow many?", "rslib.input.cancel-hint words=stop"), host.sent);
        say("cancel");
        assertFalse(number.isDone(), "'cancel' is no longer a cancel word");
        say(" Stop ");
        assertEquals(InputStatus.CANCELLED, done(number).status());
        assertFalse(host.sent.contains("rslib.input.cancelled"), "silent questions end without a message");

        host.sent.clear();
        inputs.text(alex).cancelWords().open();
        assertTrue(host.sent.isEmpty(), "no cancel words, no hint");
    }

    @Test
    void rejectionsAndMaxAttempts() {
        CompletableFuture<InputResult<Long>> future = inputs.whole(alex, 1, 10).maxAttempts(2).open();
        host.sent.clear();
        say("abc");
        assertEquals("rslib.invalid-number value=abc", host.sent.get(0));
        assertFalse(future.isDone());
        say("50");
        assertEquals("rslib.out-of-range min=1 max=10 value=50", host.sent.get(1));
        assertEquals(InputStatus.CANCELLED, done(future).status());
    }

    @Test
    void timeoutsAndTheirLimits() {
        CompletableFuture<InputResult<String>> future = inputs.text(alex).open();
        assertEquals(1200, host.timers.get(0).ticks);
        host.fireTimer();
        assertEquals(InputStatus.TIMED_OUT, done(future).status());
        assertEquals("rslib.input.timed-out", host.sent.get(host.sent.size() - 1));
        inputs.text(alex).timeout(Duration.ofMillis(10)).open();
        assertEquals(20, host.timers.get(1).ticks, "at least one second");
        inputs.text(alex).timeout(Duration.ofMillis(2_510)).open();
        assertEquals(51, host.timers.get(2).ticks);
    }

    @Test
    void leavingEndsTheQuestion() {
        CompletableFuture<InputResult<String>> future = inputs.text(alex).open();
        inputs.left(alex.getUniqueId());
        assertEquals(InputStatus.LEFT, done(future).status());
        online[0] = false;
        assertEquals(InputStatus.LEFT, done(inputs.text(alex).open()).status());
    }

    @Test
    void parserFailuresAndNullValues() {
        CompletableFuture<InputResult<Object>> nulls = inputs.custom(alex, typed -> null).maxAttempts(1).open();
        say("x");
        assertTrue(host.sent.contains("rslib.invalid-value value=x"), host.sent.toString());
        assertEquals(InputStatus.CANCELLED, done(nulls).status());
        CompletableFuture<InputResult<Object>> broken = inputs.custom(alex, typed -> {
            throw new IllegalStateException("bug");
        }).open();
        say("x");
        assertEquals(InputStatus.CANCELLED, done(broken).status());
        assertEquals(1, host.severe.stream().filter(s -> s.startsWith("SEVERE")).count(), host.severe.toString());
    }

    @Test
    void openRules() {
        InputRequest<String> request = inputs.text(alex);
        request.open();
        assertThrows(IllegalStateException.class, request::open);
        host.owns = false;
        assertThrows(IllegalStateException.class, () -> inputs.text(alex).open());
        assertThrows(IllegalArgumentException.class, () -> inputs.text(alex, 5, 2));
        assertThrows(IllegalArgumentException.class, () -> inputs.whole(alex, 5, 2));
    }

    @Test
    void resultHelpers() {
        InputResult<String> ok = new InputResult<>(InputStatus.COMPLETED, "v");
        InputResult<String> no = new InputResult<>(InputStatus.TIMED_OUT, null);
        assertEquals("v", ok.answer().orElseThrow());
        assertTrue(no.answer().isEmpty());
        StringBuilder seen = new StringBuilder();
        ok.ifCompleted(seen::append);
        no.ifCompleted(seen::append);
        assertEquals("v", seen.toString());
    }

    @Test
    void textLengthsCountCodePoints() throws InputRejected {
        assertEquals("🙂🙂", Inputs.checkText("🙂🙂", 1, 2));
        InputRejected tooLong = assertThrows(InputRejected.class, () -> Inputs.checkText("abc", 1, 2));
        assertEquals("rslib.input.too-long", tooLong.langKey());
        InputRejected tooShort = assertThrows(InputRejected.class, () -> Inputs.checkText("a", 2, 5));
        assertEquals("rslib.input.too-short", tooShort.langKey());
    }

    @Test
    void amountsAndWholeNumbers() throws InputRejected {
        assertEquals(0, new BigDecimal("2500").compareTo(Inputs.checkAmount("2.5k", BigDecimal.ZERO, null)));
        assertEquals(0, new BigDecimal("1500.25").compareTo(Inputs.checkAmount("1,500.25", null, null)));
        assertEquals("rslib.invalid-number", rejected(() -> Inputs.checkAmount("-5", BigDecimal.ZERO, null)));
        assertEquals(0, new BigDecimal("-5").compareTo(Inputs.checkAmount("-5", BigDecimal.TEN.negate(), null)));
        assertEquals("rslib.invalid-number", rejected(() -> Inputs.checkAmount("1.500", null, null)));
        assertEquals("rslib.invalid-number", rejected(() -> Inputs.checkAmount("NaN", null, null)));
        assertEquals("rslib.invalid-number", rejected(() -> Inputs.checkAmount("1e5", null, null)));
        assertEquals("rslib.number-too-small", rejected(() -> Inputs.checkAmount("1", BigDecimal.TEN, null)));
        assertEquals("rslib.out-of-range", rejected(() -> Inputs.checkAmount("11", BigDecimal.ONE, BigDecimal.TEN)));
        assertEquals(2000L, Inputs.checkWhole("2k", 0, 5000));
        assertEquals("rslib.invalid-number", rejected(() -> Inputs.checkWhole("1.5", 0, 10)));
        assertEquals("rslib.invalid-number", rejected(() -> Inputs.checkWhole("-1", 0, 10)));
        assertEquals(-1L, Inputs.checkWhole("-1", -5, 10));
    }

    private interface Check {
        Object run() throws InputRejected;
    }

    private static String rejected(Check check) {
        return assertThrows(InputRejected.class, check::run).langKey();
    }
}
