package com.rs.lib.discord;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The FIFO queue of one plugin's webhook messages and the single worker thread that sends them.
 *
 * <ul>
 *   <li>Nothing here runs on the server thread: the caller only adds to the queue. The worker
 *   is a daemon thread created on the first message and let go after 30 idle seconds.</li>
 *   <li>HTTP 429: the message goes back to the front and the worker waits {@code Retry-After}
 *   (at least one second). A success that leaves the bucket empty
 *   ({@code X-RateLimit-Remaining: 0}) makes the worker wait {@code X-RateLimit-Reset-After}
 *   before the next message, so Discord rarely has to say 429 at all.</li>
 *   <li>HTTP 404 (the webhook was deleted) or 401 (its token is wrong): the address is dead.
 *   Its waiting messages are dropped, and so is everything sent to it later, with one warning;
 *   {@link #resetDead()} (called on every plugin reload) lets it be tried again.</li>
 *   <li>Any other failure (a status that is not 2xx, the network, a bad address) drops the
 *   message with one warning per webhook, then silence. Logs never show a webhook's token:
 *   its last path segment is replaced by {@code ***}.</li>
 *   <li>At most {@value #MAX_QUEUED} messages wait; more are dropped with one warning.</li>
 * </ul>
 */
final class WebhookQueue {

    static final int MAX_QUEUED = 1000;
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    static final long MIN_RETRY_NANOS = TimeUnit.SECONDS.toNanos(1);
    /** The longest wait a rate-limit header can impose: an hour (a Cloudflare ban). */
    static final long MAX_WAIT_NANOS = TimeUnit.HOURS.toNanos(1);
    private static final long IDLE_SECONDS = 30L;
    /** How long before the end of {@link #drain}'s budget the worker stops starting new sends. */
    private static final long DRAIN_GRACE_MILLIS = 250L;

    /** One message waiting to be sent. */
    record Pending(String url, String json) {
    }

    enum Outcome { SENT, RETRY, DROPPED, INTERRUPTED }

    private final Logger logger;
    private final String owner;
    private final Supplier<WebhookTransport> transports;
    private final LongSupplier clock;
    private final Object lock = new Object();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    /** Webhook addresses that answered 404 or 401, exactly as the plugin gave them. */
    private final Set<String> dead = ConcurrentHashMap.newKeySet();

    /** Guarded by {@link #lock}. */
    private final Deque<Pending> queue = new ArrayDeque<>();
    /** Guarded by {@link #lock}. */
    private ScheduledThreadPoolExecutor executor;
    /** Guarded by {@link #lock}: a pump is scheduled or running. */
    private boolean pumping;
    /** Guarded by {@link #lock}. */
    private ScheduledFuture<?> scheduled;
    /** Guarded by {@link #lock}. */
    private boolean closed;
    /** Guarded by {@link #lock}. */
    private boolean overflowWarned;
    /** Guarded by {@link #lock}: the message being sent, out of the queue. */
    private Pending inFlight;
    /** Clock value before which nothing is sent (rate limit). */
    private volatile long notBefore;
    /** Created on the worker thread, closed by {@link #drain}. */
    private volatile WebhookTransport transport;

    WebhookQueue(Logger logger, String owner, Supplier<WebhookTransport> transports, LongSupplier clock) {
        this.logger = logger;
        this.owner = owner;
        this.transports = transports;
        this.clock = clock;
        this.notBefore = clock.getAsLong();
    }

    /** Adds a message and wakes the worker; never blocks and never touches the network. */
    void offer(String url, String json) {
        if (!isWebAddress(url)) {
            warnOnce(redact(url), "Discord webhook " + redact(url) + " is not a web address (https://...); "
                    + "messages to it are dropped. This is reported once.");
            return;
        }
        synchronized (lock) {
            if (closed) {
                warnOnce("closed", "A Discord message was sent after " + owner + " shut down; dropped.");
                return;
            }
            if (dead.contains(url)) {
                return;
            }
            if (queue.size() >= MAX_QUEUED) {
                if (!overflowWarned) {
                    overflowWarned = true;
                    logger.warning(MAX_QUEUED + " Discord messages are already waiting; new ones are dropped until they are sent.");
                }
                return;
            }
            overflowWarned = false;
            queue.addLast(new Pending(url, json));
            if (!pumping) {
                pumping = true;
                schedulePump(Math.max(0L, notBefore - clock.getAsLong()));
            }
        }
    }

    /** Lets every dead webhook be tried again; the next 404 or 401 warns again. */
    void resetDead() {
        dead.clear();
    }

    /** Number of messages waiting; for tests. */
    int waiting() {
        synchronized (lock) {
            return queue.size();
        }
    }

    /**
     * Sends what is still queued within {@code budget}, waiting a rate limit only when it fits,
     * then stops the worker and the HTTP client. What is left is counted in one warning. The
     * sending itself stays on the worker thread; the caller only waits. Later messages are
     * dropped.
     */
    void drain(Duration budget) {
        ScheduledThreadPoolExecutor worker;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            worker = executor;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
        }
        if (worker != null) {
            // The worker stops sending a little before the end, so the whole drain fits the budget.
            long end = clock.getAsLong() + budget.toNanos();
            long deadline = end - Math.min(budget.toNanos() / 4, TimeUnit.MILLISECONDS.toNanos(DRAIN_GRACE_MILLIS));
            Future<?> done = worker.submit(() -> drainUntil(deadline));
            try {
                done.get(Math.max(0L, end - clock.getAsLong()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException tooSlow) {
                done.cancel(true);
            } catch (ExecutionException failed) {
                logger.log(Level.WARNING, "Sending the last Discord messages failed", failed.getCause());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            worker.shutdownNow();
            try {
                worker.awaitTermination(Math.max(0L, end - clock.getAsLong()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        WebhookTransport open = transport;
        transport = null;
        if (open != null) {
            open.close();
        }
        int left;
        synchronized (lock) {
            // A message still in flight was cut off by the budget: it counts as not sent.
            left = queue.size() + (inFlight == null ? 0 : 1);
            queue.clear();
            inFlight = null;
        }
        if (left > 0) {
            logger.warning(left + " Discord message" + (left == 1 ? " was" : "s were") + " not sent before "
                    + owner + " shut down.");
        }
    }

    // ------------------------------------------------------------------ worker

    /** Caller holds the lock. */
    private void schedulePump(long delayNanos) {
        if (executor == null) {
            executor = newWorker(owner);
        }
        scheduled = executor.schedule(this::pump, delayNanos, TimeUnit.NANOSECONDS);
    }

    /** Sends in order until the queue is empty or a rate limit says wait; on the worker. */
    private void pump() {
        while (true) {
            Pending next;
            synchronized (lock) {
                next = closed ? null : queue.peekFirst();
                if (next == null) {
                    pumping = false;
                    return;
                }
                long wait = notBefore - clock.getAsLong();
                if (wait > 0) {
                    scheduled = executor.schedule(this::pump, wait, TimeUnit.NANOSECONDS);
                    return;
                }
                take();
            }
            if (!settle(next, deliver(next, REQUEST_TIMEOUT))) {
                synchronized (lock) {
                    pumping = false;
                }
                return;
            }
        }
    }

    /** Takes the head as the message in flight. Caller holds the lock. */
    private void take() {
        inFlight = queue.pollFirst();
    }

    /** Ends the message in flight: back to the front unless sent or dropped. False when interrupted. */
    private boolean settle(Pending message, Outcome outcome) {
        synchronized (lock) {
            inFlight = null;
            if (outcome == Outcome.RETRY || outcome == Outcome.INTERRUPTED) {
                queue.addFirst(message);
            }
        }
        return outcome != Outcome.INTERRUPTED;
    }

    /** The last sends before shutdown; on the worker. */
    private void drainUntil(long deadline) {
        while (true) {
            Pending next;
            synchronized (lock) {
                next = queue.peekFirst();
            }
            if (next == null) {
                return;
            }
            long now = clock.getAsLong();
            long wait = notBefore - now;
            if (wait > 0) {
                if (now + wait >= deadline) {
                    return;
                }
                try {
                    TimeUnit.NANOSECONDS.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            long left = deadline - now;
            if (left <= 0) {
                return;
            }
            synchronized (lock) {
                take();
            }
            if (!settle(next, deliver(next, Duration.ofNanos(Math.min(left, REQUEST_TIMEOUT.toNanos()))))) {
                return;
            }
        }
    }

    /** One POST and what it means for the queue; updates the rate-limit wait. */
    Outcome deliver(Pending message, Duration timeout) {
        WebhookTransport.Reply reply;
        try {
            reply = transport().post(message.url(), message.json(), timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Outcome.INTERRUPTED;
        } catch (IOException | RuntimeException failed) {
            String why = failed.getClass().getSimpleName()
                    + (failed.getMessage() == null ? "" : ": " + scrub(failed.getMessage(), message.url()));
            warnEndpoint(message.url(), why);
            return Outcome.DROPPED;
        }
        long now = clock.getAsLong();
        if (reply.status() == 404 || reply.status() == 401) {
            markDead(message.url(), reply.status());
            return Outcome.DROPPED;
        }
        if (reply.status() == 429) {
            notBefore = now + retryNanos(reply);
            return Outcome.RETRY;
        }
        if (reply.status() >= 200 && reply.status() < 300) {
            if (reply.remaining() == 0 && reply.resetAfter() > 0) {
                notBefore = now + toNanos(reply.resetAfter());
            }
            return Outcome.SENT;
        }
        warnEndpoint(message.url(), "HTTP " + reply.status());
        return Outcome.DROPPED;
    }

    private WebhookTransport transport() {
        WebhookTransport current = transport;
        if (current == null) {
            current = transports.get();
            transport = current;
        }
        return current;
    }

    private static ScheduledThreadPoolExecutor newWorker(String owner) {
        ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "RsLib Discord (" + owner + ")");
            thread.setDaemon(true);
            return thread;
        });
        worker.setKeepAliveTime(IDLE_SECONDS, TimeUnit.SECONDS);
        worker.allowCoreThreadTimeOut(true);
        worker.setRemoveOnCancelPolicy(true);
        return worker;
    }

    /** Stops every message to {@code url}, queued or future, with one warning. */
    private void markDead(String url, int status) {
        if (!dead.add(url)) {
            return;
        }
        int dropped = 0;
        synchronized (lock) {
            for (var it = queue.iterator(); it.hasNext(); ) {
                if (it.next().url().equals(url)) {
                    it.remove();
                    dropped++;
                }
            }
        }
        logger.warning("Discord webhook " + redact(url) + " answered HTTP " + status
                + (status == 404 ? " (unknown webhook: it was deleted)" : " (its token was refused)")
                + "; nothing more is sent to it until " + owner + " is reloaded"
                + (dropped > 0 ? " (" + dropped + " waiting message" + (dropped == 1 ? "" : "s") + " dropped)" : "")
                + ".");
    }

    private void warnEndpoint(String url, String why) {
        String shown = redact(url);
        warnOnce(shown, "Discord webhook " + shown + " failed (" + why + "); messages to it are dropped."
                + " This is reported once.");
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) {
            logger.warning(message);
        }
    }

    // ------------------------------------------------------------------ pure helpers

    /** How long a 429 makes the queue wait: {@code Retry-After}, else the bucket reset, at least a second. */
    static long retryNanos(WebhookTransport.Reply reply) {
        double seconds = reply.retryAfter() > 0 ? reply.retryAfter() : reply.resetAfter();
        return Math.max(MIN_RETRY_NANOS, toNanos(seconds));
    }

    /** Seconds (decimals allowed) to nanoseconds, within 0 and {@link #MAX_WAIT_NANOS}. */
    static long toNanos(double seconds) {
        if (!(seconds > 0)) {
            return 0L;
        }
        double nanos = seconds * 1_000_000_000d;
        return nanos >= MAX_WAIT_NANOS ? MAX_WAIT_NANOS : (long) nanos;
    }

    /** A header in seconds ({@code 1.5}, {@code 3}); -1 when it is not a number. */
    static double parseSeconds(String value) {
        if (value == null) {
            return -1;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed) ? parsed : -1;
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    static boolean isWebAddress(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    /**
     * The webhook address safe to log: query and fragment dropped and the last path segment
     * (the token) replaced by {@code ***}. An address without a path is shown as it is.
     */
    static String redact(String url) {
        if (url == null || url.isBlank()) {
            return "<empty>";
        }
        String s = stripTail(url.trim());
        int scheme = s.indexOf("://");
        int pathStart = scheme < 0 ? s.indexOf('/') : s.indexOf('/', scheme + 3);
        if (pathStart < 0) {
            return scheme < 0 ? "***" : s;
        }
        return s.substring(0, s.lastIndexOf('/') + 1) + "***";
    }

    /** {@code text} with the webhook's token, and the whole address, taken out. */
    static String scrub(String text, String url) {
        if (text == null || url == null || url.isBlank()) {
            return text;
        }
        String out = text.replace(url, redact(url));
        String s = stripTail(url.trim());
        String token = s.substring(s.lastIndexOf('/') + 1);
        int scheme = s.indexOf("://");
        boolean hasPath = (scheme < 0 ? s.indexOf('/') : s.indexOf('/', scheme + 3)) >= 0;
        if (hasPath && !token.isEmpty()) {
            out = out.replace(token, "***");
        }
        return out;
    }

    /** Without query, fragment and trailing slashes. */
    private static String stripTail(String url) {
        int end = url.length();
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        if (query >= 0) {
            end = query;
        }
        if (fragment >= 0 && fragment < end) {
            end = fragment;
        }
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }
}
