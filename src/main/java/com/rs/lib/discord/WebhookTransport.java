package com.rs.lib.discord;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** The only door to the network of the webhooks: the server uses {@link Jdk}, tests a fake. */
interface WebhookTransport {

    /**
     * POSTs {@code json} to {@code url}, waiting at most {@code timeout} for the answer.
     *
     * @throws IllegalArgumentException when {@code url} is not a usable address
     */
    Reply post(String url, String json, Duration timeout) throws IOException, InterruptedException;

    /** Stops every request in flight; the transport cannot be used afterwards. */
    void close();

    /**
     * What Discord answered: the status and its rate-limit headers, in seconds ({@code -1}
     * when the header was missing or unreadable).
     */
    record Reply(int status, double retryAfter, int remaining, double resetAfter) {

        static Reply of(int status) {
            return new Reply(status, -1, -1, -1);
        }
    }

    /** The JDK's {@link HttpClient}: 5 s to connect, redirects never followed. */
    final class Jdk implements WebhookTransport {

        static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
        private static final String USER_AGENT = "RsLib-DiscordWebhook";

        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        @Override
        public Reply post(String url, String json, Duration timeout) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            HttpHeaders headers = response.headers();
            return new Reply(response.statusCode(), seconds(headers, "Retry-After"),
                    (int) Math.min(Integer.MAX_VALUE, seconds(headers, "X-RateLimit-Remaining")),
                    seconds(headers, "X-RateLimit-Reset-After"));
        }

        @Override
        public void close() {
            client.shutdownNow();
        }

        private static double seconds(HttpHeaders headers, String name) {
            return headers.firstValue(name).map(WebhookQueue::parseSeconds).orElse(-1.0);
        }
    }
}
