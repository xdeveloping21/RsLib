package com.rs.lib.update.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * {@link HttpTransport} on the JDK's {@link HttpClient}: 5 s to connect, 10 s until the
 * response headers arrive, redirects followed (never from https to http). Text bodies are capped
 * at 16 MiB and a download must finish within three minutes.
 */
public final class JdkTransport implements HttpTransport {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DOWNLOAD_DEADLINE = Duration.ofMinutes(3);
    private static final int MAX_TEXT_BYTES = 16 << 20;
    private static final int BUFFER = 64 << 10;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public Response get(String url, Map<String, String> headers) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = client.send(request(url, headers), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            copy(in, body, MAX_TEXT_BYTES, null);
            return new Response(response.statusCode(), body.toString(StandardCharsets.UTF_8));
        }
    }

    @Override
    public long download(String url, Map<String, String> headers, Path target, long maxBytes)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> response = client.send(request(url, headers), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " downloading " + url);
            }
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declared > maxBytes) {
                throw new IOException("the file is " + declared + " bytes, more than the limit of " + maxBytes);
            }
            try (OutputStream out = Files.newOutputStream(target)) {
                return copy(in, out, maxBytes, DOWNLOAD_DEADLINE);
            }
        }
    }

    /** Stops every request in flight; the transport cannot be used afterwards. */
    public void close() {
        client.shutdownNow();
    }

    private static HttpRequest request(String url, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).GET();
        headers.forEach(builder::header);
        return builder.build();
    }

    /** Copies at most {@code max} bytes, within {@code limit} when it is not null. */
    private static long copy(InputStream in, OutputStream out, long max, Duration limit) throws IOException {
        long start = System.nanoTime();
        byte[] buffer = new byte[BUFFER];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > max) {
                throw new IOException("the response is larger than the limit of " + max + " bytes");
            }
            if (limit != null && System.nanoTime() - start > limit.toNanos()) {
                throw new IOException("the download took too long");
            }
            out.write(buffer, 0, read);
        }
        return total;
    }
}
