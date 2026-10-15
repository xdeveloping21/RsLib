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

    /** Answers {@code url} with {@code status} and {@code body}; other URLs get a 404. */
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
        return answer == null ? new Response(404, "{\"message\":\"Not Found\"}") : answer;
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

    /** A JSON release list with one entry per tag. */
    public static String releaseList(List<String> tags) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append("{\"tag_name\":\"").append(tags.get(i)).append("\",\"html_url\":\"https://github.com/o/r/releases/tag/")
                    .append(tags.get(i)).append("\",\"body\":\"notes\"}");
        }
        return out.append(']').toString();
    }

    /** The URL of page {@code page} of the release list of {@code repo}. */
    public static String pageUrl(String repo, int page) {
        return GitHubClient.API_ROOT + "/repos/" + repo + "/releases?per_page=100&page=" + page;
    }
}
