package com.rs.lib.update.internal;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * The few GitHub REST calls the update system makes, over an {@link HttpTransport}.
 *
 * <p>Every API call sends {@code Accept: application/vnd.github+json},
 * {@code X-GitHub-Api-Version: 2022-11-28}, {@code User-Agent: RsLib-UpdateChecker} and, when
 * a token is given, {@code Authorization: Bearer <token>}. A status other than 200 or a body
 * that is not the expected JSON is an {@link IOException} whose message never holds the token.</p>
 */
public final class GitHubClient {

    public static final String API_ROOT = "https://api.github.com";
    static final String USER_AGENT = "RsLib-UpdateChecker";
    static final String API_VERSION = "2022-11-28";

    private final HttpTransport transport;

    /**
     * One page of the release list.
     *
     * @param releases the releases that have a tag
     * @param entries  how many entries the page held, skipped ones included (for pagination)
     */
    public record Page(List<Release> releases, int entries) {
    }

    public GitHubClient(HttpTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /** {@code /repos/{repo}/releases/latest}; fails when the release has no {@code tag_name}. */
    public Release latest(String repo, String token) throws IOException, InterruptedException {
        JsonObject release = object(get("/repos/" + repo + "/releases/latest", token));
        String tag = string(release, "tag_name");
        if (tag == null) {
            throw new IOException("the latest release of " + repo + " has no tag_name");
        }
        return new Release(tag, string(release, "html_url"));
    }

    /** One page of {@code /repos/{repo}/releases}; releases without a {@code tag_name} are skipped. */
    public Page releases(String repo, int page, int perPage, String token)
            throws IOException, InterruptedException {
        JsonElement root = parse(get("/repos/" + repo + "/releases?per_page=" + perPage + "&page=" + page, token));
        if (!root.isJsonArray()) {
            throw new IOException("the release list of " + repo + " is not a JSON array");
        }
        JsonArray array = root.getAsJsonArray();
        List<Release> out = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                String tag = string(element.getAsJsonObject(), "tag_name");
                if (tag != null) {
                    out.add(new Release(tag, string(element.getAsJsonObject(), "html_url")));
                }
            }
        }
        return new Page(List.copyOf(out), array.size());
    }

    /** {@code /repos/{repo}/releases/tags/{tag}} as a JSON object. */
    public JsonObject releaseByTag(String repo, String tag, String token) throws IOException, InterruptedException {
        return object(get("/repos/" + repo + "/releases/tags/"
                + URLEncoder.encode(tag, StandardCharsets.UTF_8), token));
    }

    /** Downloads a release asset (no token is sent); see {@link HttpTransport#download}. */
    public long download(String url, Path target, long maxBytes) throws IOException, InterruptedException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/octet-stream");
        headers.put("User-Agent", USER_AGENT);
        return transport.download(url, headers, target, maxBytes);
    }

    /** The headers of an API call; {@code Authorization} only for a token that is not blank. */
    static Map<String, String> headers(String token) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", API_VERSION);
        headers.put("User-Agent", USER_AGENT);
        if (token != null && !token.isBlank()) {
            headers.put("Authorization", "Bearer " + token.trim());
        }
        return headers;
    }

    private String get(String path, String token) throws IOException, InterruptedException {
        HttpTransport.Response response = transport.get(API_ROOT + path, headers(token));
        if (response.status() != 200) {
            throw new IOException("HTTP " + response.status() + " from " + API_ROOT + path);
        }
        return response.body();
    }

    /** {@code value} of a string member, or null when it is missing or not a string. */
    public static String string(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static JsonElement parse(String body) throws IOException {
        try {
            return JsonParser.parseString(body == null ? "" : body);
        } catch (JsonParseException e) {
            throw new IOException("GitHub answered with unreadable JSON");
        }
    }

    private static JsonObject object(String body) throws IOException {
        JsonElement root = parse(body);
        if (!root.isJsonObject()) {
            throw new IOException("GitHub answered with JSON that is not an object");
        }
        return root.getAsJsonObject();
    }
}
