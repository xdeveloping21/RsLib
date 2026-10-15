package com.rs.lib.update.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** An offline {@link HttpTransport}: fixed answers by URL, and a log of every request. */
public final class FakeTransport implements HttpTransport {

    /** One request as it was sent. */
    public record Sent(String url, Map<String, String> headers) {
    }

    private final Map<String, Response> answers = new ConcurrentHashMap<>();
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    public final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());

    /** Answers {@code url} with {@code status} and {@code body}; other URLs get the site's 404. */
    public FakeTransport answer(String url, int status, String body) {
        answers.put(url, new Response(status, body));
        return this;
    }

    public FakeTransport answer(String url, String body) {
        return answer(url, 200, body);
    }

    /** Serves {@code bytes} as a download from {@code url}. */
    public FakeTransport file(String url, byte[] bytes) {
        files.put(url, bytes.clone());
        return this;
    }

    /** How many requests went to {@code url}. */
    public long count(String url) {
        synchronized (sent) {
            return sent.stream().filter(s -> s.url().equals(url)).count();
        }
    }

    @Override
    public Response get(String url, Map<String, String> headers) throws IOException, InterruptedException {
        sent.add(new Sent(url, Map.copyOf(headers)));
        Response answer = answers.get(url);
        return answer == null ? new Response(404, "{\"error\":\"unknown product\"}") : answer;
    }

    @Override
    public long download(String url, Map<String, String> headers, Path target, long maxBytes) throws IOException {
        sent.add(new Sent(url, Map.copyOf(headers)));
        byte[] bytes = files.get(url);
        if (bytes == null) {
            throw new IOException("HTTP 404 downloading " + url);
        }
        if (bytes.length > maxBytes) {
            throw new IOException("the response is larger than the limit of " + maxBytes + " bytes");
        }
        Files.write(target, bytes);
        return bytes.length;
    }

    /** The answer of {@code /v1/releases/{product}} for one jar, as the site writes it. */
    public static String release(String product, String version, String sha256, long size) {
        return "{\"product\":\"" + product + "\",\"version\":\"" + version + "\",\"sha256\":\"" + sha256
                + "\",\"size\":" + size + ",\"fileName\":\"" + product + "-" + version
                + ".jar\",\"uploadedAt\":1760000000000}";
    }

    /** {@link #release(String, String, String, long)} with a placeholder digest and size. */
    public static String release(String product, String version) {
        return release(product, version, "ab".repeat(32), 123_456);
    }
}
