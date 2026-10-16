package com.rs.lib.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DiscordWebhookTest {

    private static final String TOKEN = "s3cr3t-T0ken_abcdefghijklmnop";
    private static final String HOOK = "https://discord.com/api/webhooks/123456/" + TOKEN;
    private static final String OTHER = "https://discord.com/api/webhooks/999/other-token";
    /** What every payload ends with when the plugin allowed no ping. */
    private static final String NO_PINGS = "\"allowed_mentions\":{\"parse\":[]}";

    private final List<String> warnings = Collections.synchronizedList(new ArrayList<>());
    private FakeTransport transport;
    private WebhookQueue queue;
    private DiscordWebhook discord;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        transport = new FakeTransport();
        queue = new WebhookQueue(logger, "TestPlugin", () -> transport, System::nanoTime);
        discord = new DiscordWebhook(queue);
    }

    @AfterEach
    void tearDown() {
        discord.drain();
    }

    // ---------------------------------------------------------------- JSON

    @Test
    void messageJsonHasDiscordsKeys() {
        DiscordWebhook.Embed embed = discord.embed().title("T").url("https://x.y/t").description("D").color(0xF1C40F)
                .footer("F", "https://x.y/f.png").author("A", "https://x.y/a", "https://x.y/a.png")
                .thumbnail("https://x.y/th.png").image("https://x.y/im.png").field("N", "V", true);
        String json = discord.message(HOOK).content("hi").username("Bot").avatarUrl("https://x.y/av.png")
                .embed(embed).toJson();

        assertEquals("{\"content\":\"hi\",\"username\":\"Bot\",\"avatar_url\":\"https://x.y/av.png\",\"embeds\":[{"
                + "\"title\":\"T\",\"url\":\"https://x.y/t\",\"description\":\"D\",\"color\":15844367,"
                + "\"footer\":{\"text\":\"F\",\"icon_url\":\"https://x.y/f.png\"},"
                + "\"author\":{\"name\":\"A\",\"url\":\"https://x.y/a\",\"icon_url\":\"https://x.y/a.png\"},"
                + "\"thumbnail\":{\"url\":\"https://x.y/th.png\"},\"image\":{\"url\":\"https://x.y/im.png\"},"
                + "\"fields\":[{\"name\":\"N\",\"value\":\"V\",\"inline\":true}]}]," + NO_PINGS + "}", json);
    }

    @Test
    void partsLeftOutAreNotWritten() {
        assertEquals("{\"content\":\"hi\"," + NO_PINGS + "}", discord.message(HOOK).content("hi").toJson());
        assertEquals("{\"embeds\":[{\"footer\":{\"text\":\"f\"}}]," + NO_PINGS + "}",
                discord.message(HOOK).embed(discord.embed().footer("f")).toJson());
    }

    /** Blank strings (an empty config value) are left out exactly like null. */
    @Test
    void blankValuesAreLeftOutLikeNull() {
        DiscordWebhook.Embed embed = discord.embed().title("T").url(" ").footer("F", "").thumbnail("")
                .image("  ").author("A", "", " ");
        String json = discord.message(HOOK).content("hi").username(" ").avatarUrl("").embed(embed).toJson();
        assertEquals("{\"content\":\"hi\",\"embeds\":[{\"title\":\"T\",\"footer\":{\"text\":\"F\"},"
                + "\"author\":{\"name\":\"A\"}}]," + NO_PINGS + "}", json);

        String noAuthor = discord.message(HOOK).embed(discord.embed().title("T")
                .author(" ", "https://x.y/a", "https://x.y/a.png").footer("", "https://x.y/f.png")).toJson();
        assertEquals("{\"embeds\":[{\"title\":\"T\"}]," + NO_PINGS + "}", noAuthor, "a blank name drops the whole author bar");

        String nullAuthor = discord.message(HOOK).embed(discord.embed().title("T")
                .author(null, "https://x.y/a", "https://x.y/a.png")).toJson();
        assertEquals("{\"embeds\":[{\"title\":\"T\"}]," + NO_PINGS + "}", nullAuthor);
    }

    @Test
    void textIsEscaped() {
        String json = discord.message(HOOK).content("a \"q\" \\ b\nc\td\u0001\u2028").toJson();
        assertEquals("{\"content\":\"a \\\"q\\\" \\\\ b\\nc\\td\\u0001\\u2028\"," + NO_PINGS + "}", json);
    }

    @Test
    void timestampIsIso8601AndColorKeepsOnlyRgb() {
        String json = discord.message(HOOK).embed(discord.embed().timestampNow().color(0xFF123456)).toJson();
        assertTrue(json.matches(".*\"timestamp\":\"\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(\\.\\d+)?Z\".*"), json);
        assertTrue(json.contains("\"color\":" + 0x123456), json);
    }

    @Test
    void atMostTenEmbedsAndTwentyFiveFields() {
        DiscordWebhook.Message message = discord.message(HOOK);
        for (int i = 0; i < 12; i++) {
            message.embed(discord.embed().title("e" + i));
        }
        DiscordWebhook.Embed embed = discord.embed();
        for (int i = 0; i < 30; i++) {
            embed.field("n" + i, "v", false);
        }
        String json = message.toJson();
        assertTrue(json.contains("\"e9\"") && !json.contains("\"e10\""));
        String fields = discord.message(HOOK).embed(embed).toJson();
        assertTrue(fields.contains("\"n24\"") && !fields.contains("\"n25\""));
    }

    @Test
    void textLongerThanDiscordAcceptsIsCutAndEmptyFieldsAreFilled() {
        String json = discord.message(HOOK).content("x".repeat(2500))
                .embed(discord.embed().field("", null, false)).toJson();
        assertTrue(json.contains("\"" + "x".repeat(2000) + "\""));
        assertFalse(json.contains("x".repeat(2001)));
        assertTrue(json.contains("\"name\":\"\u200B\",\"value\":\"\u200B\""));
        assertEquals("a", Json.cut("a\uD83D\uDE00", 2), "a surrogate pair is never split");
    }

    // ---------------------------------------------------------------- mentions

    @Test
    void nothingPingsByDefaultWhateverTheTextSays() {
        String json = discord.message(HOOK).content("@everyone @here <@123> <@&456>").toJson();
        assertTrue(json.endsWith(",\"allowed_mentions\":{\"parse\":[]}}"), json);
        String embedOnly = discord.message(HOOK).embed(discord.embed().description("@everyone")).toJson();
        assertTrue(embedOnly.endsWith("]," + NO_PINGS + "}"), embedOnly);
    }

    @Test
    void onlyThePingsThePluginAllowsAreAllowed() {
        String json = discord.message(HOOK).content("<@123> <@&456> @everyone")
                .allowUserPing("123").allowUserPing(" 123 ").allowUserPing("abc").allowUserPing(null)
                .allowUserPing("1".repeat(21)).allowRolePing("456").allowRolePing("").toJson();
        assertTrue(json.endsWith(",\"allowed_mentions\":{\"parse\":[],\"users\":[\"123\"],\"roles\":[\"456\"]}}"), json);

        String everyone = discord.message(HOOK).content("@here").allowEveryonePing().toJson();
        assertTrue(everyone.endsWith(",\"allowed_mentions\":{\"parse\":[\"everyone\"]}}"), everyone);

        DiscordWebhook.Message many = discord.message(HOOK).content("x");
        for (int i = 0; i < 150; i++) {
            many.allowRolePing(String.valueOf(1000 + i));
        }
        String capped = many.toJson();
        assertTrue(capped.contains("\"1099\"") && !capped.contains("\"1100\""), "at most 100 ids");
    }

    // ---------------------------------------------------------------- dead webhooks

    @Test
    void a404StopsEveryMessageToThatWebhookQueuedOrLaterUntilReset() throws InterruptedException {
        transport.script.add(reply(404));
        transport.delayNanos = TimeUnit.MILLISECONDS.toNanos(200);
        CountDownLatch started = new CountDownLatch(1);
        transport.onStart = started;
        transport.expect(2);
        discord.send(HOOK, "a");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        discord.send(HOOK, "b");
        discord.send(OTHER, "c");
        discord.send(HOOK, "d");
        transport.await();
        transport.onStart = null;
        transport.delayNanos = 0;

        transport.expect(1);
        discord.send(HOOK, "e");
        discord.send(OTHER, "f");
        transport.await();

        assertEquals(List.of(HOOK, OTHER, OTHER), transport.urls());
        assertEquals(List.of(content("a"), content("c"), content("f")), transport.bodies());
        assertEquals(1, warnings.size(), warnings.toString());
        String warning = warnings.get(0);
        assertTrue(warning.contains("webhooks/123456/***") && warning.contains("HTTP 404"), warning);
        assertTrue(warning.contains("2 waiting messages dropped"), warning);
        assertFalse(warning.contains(TOKEN), warning);
        assertEquals(0, queue.waiting());

        discord.resetDeadWebhooks();
        transport.expect(1);
        discord.send(HOOK, "g");
        transport.await();
        assertEquals(content("g"), transport.bodies().get(3), "a reload lets the webhook be tried again");
    }

    @Test
    void a401IsDeadTooAndWarnsAgainAfterAReset() throws InterruptedException {
        transport.script.add(reply(401));
        transport.expect(2);
        discord.send(HOOK, "a");
        discord.send(HOOK, "b");
        discord.send(OTHER, "c");
        transport.await();
        assertEquals(List.of(HOOK, OTHER), transport.urls());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("HTTP 401"), warnings.get(0));

        discord.resetDeadWebhooks();
        transport.script.add(reply(401));
        transport.expect(2);
        discord.send(HOOK, "d");
        discord.send(OTHER, "e");
        transport.await();
        assertEquals(2, warnings.size(), "still dead after the reload: one more warning");
    }

    @Test
    void otherFailuresDropOnlyThatMessage() throws InterruptedException {
        transport.script.add(reply(500));
        transport.script.add(reply(400));
        transport.expect(3);
        discord.send(HOOK, "a");
        discord.send(HOOK, "b");
        discord.send(HOOK, "c");
        transport.await();
        assertEquals(List.of(content("a"), content("b"), content("c")), transport.bodies());
        assertEquals(1, warnings.size(), warnings.toString());
    }

    // ---------------------------------------------------------------- tokens never logged (trap 13.6)

    @Test
    void redactHidesTheToken() {
        assertEquals("https://discord.com/api/webhooks/123456/***", WebhookQueue.redact(HOOK));
        assertEquals("https://discord.com/api/webhooks/123456/***", WebhookQueue.redact(HOOK + "/?wait=true"));
        assertEquals("https://discord.com/api/webhooks/123456/***", WebhookQueue.redact(HOOK + "#x"));
        assertEquals("https://discord.com", WebhookQueue.redact("https://discord.com/"));
        assertEquals("***", WebhookQueue.redact("garbage"));
        assertEquals("<empty>", WebhookQueue.redact(" "));
        assertEquals("<empty>", WebhookQueue.redact(null));
        assertEquals("Illegal character at https://discord.com/api/webhooks/123456/***",
                WebhookQueue.scrub("Illegal character at " + HOOK, HOOK));
        assertEquals("bad *** here", WebhookQueue.scrub("bad " + TOKEN + " here", HOOK));
    }

    @Test
    void failuresWarnOncePerWebhookWithoutTheToken() throws InterruptedException {
        transport.script.add(reply(400));
        transport.script.add(reply(500));
        transport.script.add(new Boom(new IOException("connect to " + OTHER + " refused")));
        transport.script.add(reply(400));
        transport.expect(4);
        discord.send(HOOK, "a");
        discord.send(HOOK, "b");
        discord.send(OTHER, "c");
        discord.send("ftp://x/" + TOKEN, "d");
        discord.send(OTHER, "e");
        transport.await();

        assertEquals(3, warnings.size(), warnings.toString());
        for (String warning : warnings) {
            assertFalse(warning.contains(TOKEN), warning);
            assertFalse(warning.contains("other-token"), warning);
        }
    }

    // ---------------------------------------------------------------- queue

    @Test
    void emptyMessagesAreNotSent() throws InterruptedException {
        discord.send(HOOK, "");
        discord.send(HOOK, "   ");
        discord.send(HOOK, null);
        discord.message(HOOK).username("only a name").send();
        transport.expect(1);
        discord.send(HOOK, "real");
        transport.await();

        assertEquals(List.of(content("real")), transport.bodies());
    }

    @Test
    void messagesGoOutInOrderOffTheCallingThread() throws InterruptedException {
        transport.expect(5);
        for (int i = 0; i < 5; i++) {
            discord.send(HOOK, "m" + i);
        }
        transport.await();

        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add(content("m" + i));
        }
        assertEquals(expected, transport.bodies());
        for (Sent sent : transport.sent) {
            assertNotEquals(Thread.currentThread().getName(), sent.thread());
            assertTrue(sent.thread().startsWith("RsLib Discord"), sent.thread());
        }
    }

    @Test
    void a429PutsTheMessageBackInFrontAndWaitsRetryAfterAtLeastOneSecond() throws InterruptedException {
        transport.script.add(new WebhookTransport.Reply(429, 0.2, 0, 0.2));
        transport.expect(3);
        discord.send(HOOK, "first");
        discord.send(HOOK, "second");
        transport.await();

        assertEquals(List.of(content("first"), content("first"), content("second")),
                transport.bodies());
        long waited = transport.sent.get(1).at() - transport.sent.get(0).at();
        assertTrue(waited >= TimeUnit.MILLISECONDS.toNanos(950), "waited " + waited / 1_000_000 + " ms");
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void anEmptyBucketPacesTheNextMessage() throws InterruptedException {
        transport.script.add(new WebhookTransport.Reply(204, -1, 0, 0.4));
        transport.expect(2);
        discord.send(HOOK, "a");
        discord.send(HOOK, "b");
        transport.await();

        long waited = transport.sent.get(1).at() - transport.sent.get(0).at();
        assertTrue(waited >= TimeUnit.MILLISECONDS.toNanos(350), "waited " + waited / 1_000_000 + " ms");
    }

    @Test
    void drainSendsWhatIsLeftAndThenDropsNewMessages() {
        transport.delayNanos = TimeUnit.MILLISECONDS.toNanos(50);
        for (int i = 0; i < 5; i++) {
            discord.send(HOOK, "m" + i);
        }
        discord.drain();

        assertEquals(5, transport.sent.size());
        assertTrue(transport.closed);
        discord.send(HOOK, "late");
        assertEquals(0, queue.waiting());
        assertEquals(5, transport.sent.size());
    }

    @Test
    void drainDoesNotWaitARateLimitLongerThanItsBudgetAndCountsTheRest() {
        transport.script.add(new WebhookTransport.Reply(429, 60, 0, 60));
        CountDownLatch first = transport.expect(1);
        discord.send(HOOK, "a");
        discord.send(HOOK, "b");
        try {
            assertTrue(first.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        long start = System.nanoTime();
        discord.drain();
        long took = System.nanoTime() - start;

        assertTrue(took < TimeUnit.SECONDS.toNanos(2), "took " + took / 1_000_000 + " ms");
        assertEquals(1, transport.sent.size());
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("2 Discord messages were not sent")), warnings.toString());
    }

    @Test
    void drainNeverTakesLongerThanThreeSecondsEvenWithAHangingRequest() throws InterruptedException {
        transport.delayNanos = TimeUnit.SECONDS.toNanos(30);
        CountDownLatch started = new CountDownLatch(1);
        transport.onStart = started;
        discord.send(HOOK, "stuck");
        discord.send(HOOK, "behind");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        long start = System.nanoTime();
        discord.drain();
        long took = System.nanoTime() - start;

        assertTrue(took <= TimeUnit.MILLISECONDS.toNanos(3100), "took " + took / 1_000_000 + " ms");
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("2 Discord messages were not sent")), warnings.toString());
        assertTrue(transport.closed);
    }

    @Test
    void drainOfAnUnusedWebhookStartsNothing() {
        discord.drain();
        assertFalse(transport.created);
    }

    @Test
    void rateLimitArithmetic() {
        assertEquals(TimeUnit.SECONDS.toNanos(1), WebhookQueue.retryNanos(new WebhookTransport.Reply(429, 0.25, -1, -1)));
        assertEquals(TimeUnit.MILLISECONDS.toNanos(2500), WebhookQueue.retryNanos(new WebhookTransport.Reply(429, 2.5, -1, -1)));
        assertEquals(TimeUnit.SECONDS.toNanos(3), WebhookQueue.retryNanos(new WebhookTransport.Reply(429, -1, 0, 3)));
        assertEquals(TimeUnit.SECONDS.toNanos(1), WebhookQueue.retryNanos(WebhookTransport.Reply.of(429)));
        assertEquals(WebhookQueue.MAX_WAIT_NANOS, WebhookQueue.toNanos(1e12));
        assertEquals(1.5, WebhookQueue.parseSeconds(" 1.5 "));
        assertEquals(-1, WebhookQueue.parseSeconds("soon"));
        assertEquals(-1, WebhookQueue.parseSeconds("NaN"));
    }

    // ---------------------------------------------------------------- fakes

    /** The payload of a plain text message. */
    private static String content(String text) {
        return "{\"content\":\"" + text + "\"," + NO_PINGS + "}";
    }

    private static WebhookTransport.Reply reply(int status) {
        return WebhookTransport.Reply.of(status);
    }

    /** A scripted reply that throws instead. */
    private static final class Boom extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Boom(IOException cause) {
            super(cause);
        }
    }

    private record Sent(String url, String json, String thread, long at) {
    }

    /** Answers 204 unless the script says otherwise; counts down a latch per request. */
    private static final class FakeTransport implements WebhookTransport {

        final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
        final Deque<Object> script = new ArrayDeque<>();
        volatile CountDownLatch latch = new CountDownLatch(0);
        volatile long delayNanos;
        volatile CountDownLatch onStart;
        volatile boolean closed;
        volatile boolean created = false;

        CountDownLatch expect(int requests) {
            latch = new CountDownLatch(requests);
            return latch;
        }

        void await() throws InterruptedException {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "requests did not arrive: " + sent.size());
        }

        List<String> urls() {
            synchronized (sent) {
                return sent.stream().map(Sent::url).toList();
            }
        }

        List<String> bodies() {
            synchronized (sent) {
                return sent.stream().map(Sent::json).toList();
            }
        }

        @Override
        public Reply post(String url, String json, Duration timeout) throws IOException, InterruptedException {
            created = true;
            CountDownLatch starting = onStart;
            if (starting != null) {
                starting.countDown();
            }
            if (delayNanos > 0) {
                TimeUnit.NANOSECONDS.sleep(delayNanos);
            }
            if (!url.startsWith("https://")) {
                throw new IllegalArgumentException("bad address " + url);
            }
            sent.add(new Sent(url, json, Thread.currentThread().getName(), System.nanoTime()));
            Object next;
            synchronized (script) {
                next = script.poll();
            }
            try {
                if (next instanceof Boom boom) {
                    throw (IOException) boom.getCause();
                }
                return next == null ? Reply.of(204) : (Reply) next;
            } finally {
                latch.countDown();
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
