package com.rs.lib.update.internal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.rs.lib.update.Versions;

/**
 * The two calls the update system makes to rsdevelopment.dev, over an {@link HttpTransport}.
 *
 * <ul>
 *   <li>{@code GET /v1/releases/{id}}: the one current jar of a product, as JSON
 *   ({@code product}, {@code version}, {@code sha256}, {@code size}, {@code fileName},
 *   {@code uploadedAt}).</li>
 *   <li>{@code GET /v1/releases/{id}/download}: that jar's bytes (only {@code rslib} is served).</li>
 * </ul>
 *
 * <p>Both go to {@link #BASE_URL} and nowhere else and send {@code User-Agent: RsLib/<version>}.
 * Redirects are not followed (see {@link JdkTransport}): a 3xx fails like any status other than
 * 200, and so does an answer that is not the expected JSON, as an {@link IOException}.</p>
 */
public final class SiteClient {

    public static final String BASE_URL = "https://rsdevelopment.dev";
    static final String RELEASES = "/v1/releases/";
    private static final Pattern ID = Pattern.compile("[a-z0-9-]{1,40}");
    private static final int MAX_QUOTED_CHARS = 120;

    private final HttpTransport transport;
    private final String userAgent;

    /**
     * The current jar of a product, as the site describes it.
     *
     * @param product    the product id, the one that was asked for
     * @param version    a valid version (see {@link Versions#isValid}), exactly as written
     * @param sha256     the jar's SHA-256, 64 lower-case hex digits
     * @param size       the jar's size in bytes
     * @param fileName   the jar's name on the site, or null; informative only, never used as a path
     * @param uploadedAt when it was uploaded, in epoch milliseconds, or 0 when not given
     */
    public record Info(String product, String version, String sha256, long size, String fileName, long uploadedAt) {
    }

    /** @param userAgent see {@link #userAgent(String)} */
    public SiteClient(HttpTransport transport, String userAgent) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
    }

    /** {@code RsLib/<version>}, the User-Agent of every request. */
    public static String userAgent(String rslibVersion) {
        return "RsLib/" + (rslibVersion == null || rslibVersion.isBlank() ? "unknown" : rslibVersion.trim());
    }

    /** True for an id of 1 to 40 characters among {@code a-z}, {@code 0-9} and {@code -}. */
    public static boolean isValidId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** {@code id} trimmed and in lower case when that is a valid product id, else null. */
    public static String normalizeId(String id) {
        if (id == null) {
            return null;
        }
        String lower = id.trim().toLowerCase(Locale.ROOT);
        return isValidId(lower) ? lower : null;
    }

    /** {@code https://rsdevelopment.dev/v1/releases/{id}}. */
    public static String releaseUrl(String id) {
        return BASE_URL + RELEASES + checked(id);
    }

    /** {@code https://rsdevelopment.dev/v1/releases/{id}/download}. */
    public static String downloadUrl(String id) {
        return releaseUrl(id) + "/download";
    }

    /** The current jar of product {@code id}; fails on any status but 200 or on unexpected JSON. */
    public Info release(String id) throws IOException, InterruptedException {
        String url = releaseUrl(id);
        HttpTransport.Response response = transport.get(url, headers("application/json"));
        if (response.status() != 200) {
            String error = error(response.body());
            throw new IOException("HTTP " + response.status() + " from " + url
                    + (error == null ? "" : " (" + error + ")"));
        }
        return parse(id, response.body());
    }

    /** Downloads the current jar of product {@code id}; see {@link HttpTransport#download}. */
    public long download(String id, Path target, long maxBytes) throws IOException, InterruptedException {
        return transport.download(downloadUrl(id), headers("application/java-archive"), target, maxBytes);
    }

    private Map<String, String> headers(String accept) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", accept);
        headers.put("User-Agent", userAgent);
        return headers;
    }

    /** Reads the answer for product {@code id}; only {@code fileName} and {@code uploadedAt} may be missing. */
    static Info parse(String id, String body) throws IOException {
        JsonObject root = object(body);
        String product = string(root, "product");
        if (!id.equals(product)) {
            throw new IOException("the site answered for product '" + shorten(product) + "', not " + id);
        }
        String version = string(root, "version");
        if (!Versions.isValid(version) || !version.equals(version.strip())) {
            throw new IOException("the site gives no valid version for " + id + " ('" + shorten(version) + "')");
        }
        String sha256 = string(root, "sha256");
        if (sha256 == null || sha256.length() != 64 || !isHex(sha256)) {
            throw new IOException("the site gives no valid SHA-256 for " + id);
        }
        long size = number(root, "size");
        if (size < 0) {
            throw new IOException("the site gives no valid size for " + id);
        }
        long uploadedAt = number(root, "uploadedAt");
        return new Info(product, version, sha256.toLowerCase(Locale.ROOT), size, string(root, "fileName"),
                Math.max(0, uploadedAt));
    }

    private static JsonObject object(String body) throws IOException {
        try {
            JsonElement root = JsonParser.parseString(body == null ? "" : body);
            if (!root.isJsonObject()) {
                throw new IOException("the site answered with JSON that is not an object");
            }
            return root.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IOException("the site answered with unreadable JSON");
        }
    }

    /** The {@code error} member of an error answer, shortened, or null. */
    private static String error(String body) {
        try {
            JsonElement root = JsonParser.parseString(body == null ? "" : body);
            return root.isJsonObject() ? shorten(string(root.getAsJsonObject(), "error")) : null;
        } catch (JsonParseException e) {
            return null;
        }
    }

    /** {@code value} of a string member, or null when it is missing or not a string. */
    private static String string(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    /** A whole, non-negative number member, or -1 when it is missing or anything else. */
    private static long number(JsonObject object, String member) {
        JsonElement value = object.get(member);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return -1;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        try {
            return Math.max(-1, primitive.getAsBigDecimal().longValueExact());
        } catch (ArithmeticException | NumberFormatException notWhole) {
            return -1;
        }
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!HexFormat.isHexDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Text from the site as it may appear in a log line: one line, at most 120 characters. */
    private static String shorten(String text) {
        if (text == null) {
            return null;
        }
        String line = text.replaceAll("\\p{Cntrl}", " ").strip();
        return line.length() > MAX_QUOTED_CHARS ? line.substring(0, MAX_QUOTED_CHARS) + "..." : line;
    }

    private static String checked(String id) {
        if (!isValidId(id)) {
            throw new IllegalArgumentException("not a product id: " + id);
        }
        return id;
    }
}
