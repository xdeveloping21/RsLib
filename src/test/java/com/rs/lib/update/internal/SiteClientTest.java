package com.rs.lib.update.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link SiteClient}: addresses, headers, product ids and reading the site's JSON. */
class SiteClientTest {

    private static final String URL = "https://rsdevelopment.dev/v1/releases/rskills";
    private static final String SHA = "0123456789abcdef".repeat(4);

    @TempDir
    Path dir;

    private final FakeTransport http = new FakeTransport();
    private final SiteClient site = new SiteClient(http, SiteClient.userAgent("1.2.3"));

    @Test
    void releaseIsReadFromTheSite() throws Exception {
        http.answer(URL, "{\"product\":\"rskills\",\"version\":\"1.0.1\",\"sha256\":\"" + SHA.toUpperCase()
                + "\",\"size\":123456,\"fileName\":\"RsKills-1.0.1.jar\",\"uploadedAt\":1760000000000,\"extra\":[1]}");
        SiteClient.Info info = site.release("rskills");
        assertEquals(new SiteClient.Info("rskills", "1.0.1", SHA, 123_456, "RsKills-1.0.1.jar", 1_760_000_000_000L), info);

        Map<String, String> headers = http.sent.get(0).headers();
        assertEquals(Map.of("Accept", "application/json", "User-Agent", "RsLib/1.2.3"), headers);
        assertEquals(URL, http.sent.get(0).url());
    }

    @Test
    void fileNameAndUploadTimeAreOptional() throws Exception {
        http.answer(URL, "{\"product\":\"rskills\",\"version\":\"2.0.0-beta.1\",\"sha256\":\"" + SHA
                + "\",\"size\":20000}");
        SiteClient.Info info = site.release("rskills");
        assertEquals("2.0.0-beta.1", info.version());
        assertNull(info.fileName());
        assertEquals(0, info.uploadedAt());
    }

    @Test
    void anythingButTheExpectedAnswerIsAnIOException() {
        assertEquals("HTTP 404 from " + URL + " (unknown product)", failure(404, "{\"error\":\"unknown product\"}"));
        assertEquals("HTTP 500 from " + URL, failure(500, "<html>boom</html>"));
        assertEquals("HTTP 301 from " + URL, failure(301, ""));
        assertTrue(failure(404, "{\"error\":\"" + "x".repeat(500) + "\\nmore\"}").length() < 200);

        String ok = ",\"sha256\":\"" + SHA + "\",\"size\":20000}";
        for (String body : List.of(
                "",
                "not json",
                "[1,2]",
                "{\"product\":\"rskills\",\"version\":\"1.0.0\"",
                "{\"product\":\"rsother\",\"version\":\"1.0.0\"" + ok,
                "{\"product\":\"RsKills\",\"version\":\"1.0.0\"" + ok,
                "{\"version\":\"1.0.0\"" + ok,
                "{\"product\":\"rskills\"" + ok,
                "{\"product\":\"rskills\",\"version\":\"nightly\"" + ok,
                "{\"product\":\"rskills\",\"version\":\" 1.0.0\"" + ok,
                "{\"product\":\"rskills\",\"version\":1" + ok,
                "{\"product\":\"rskills\",\"version\":\"1.0.0\",\"sha256\":\"abc\",\"size\":20000}",
                "{\"product\":\"rskills\",\"version\":\"1.0.0\",\"sha256\":\"" + SHA.replace('a', 'g') + "\",\"size\":20000}",
                "{\"product\":\"rskills\",\"version\":\"1.0.0\",\"size\":20000}",
                "{\"product\":\"rskills\",\"version\":\"1.0.0\",\"sha256\":\"" + SHA + "\"}",
                "{\"product\":\"rskills\",\"version\":\"1.0.0\",\"sha256\":\"" + SHA + "\",\"size\":-1}",
                "{\"product\":\"rskills\",\"version\":\"1.0.0\",\"sha256\":\"" + SHA + "\",\"size\":1.5}",
                "{\"product\":\"rskills\",\"version\":\"1.0.0\",\"sha256\":\"" + SHA + "\",\"size\":\"20000\"}")) {
            http.answer(URL, body);
            assertThrows(IOException.class, () -> site.release("rskills"), body);
        }
    }

    private String failure(int status, String body) {
        http.answer(URL, status, body);
        return assertThrows(IOException.class, () -> site.release("rskills")).getMessage();
    }

    @Test
    void productIds() {
        assertEquals("rslib", SiteClient.normalizeId("RsLib"));
        assertEquals("rs-kills2", SiteClient.normalizeId("  rs-kills2 "));
        assertEquals("a".repeat(40), SiteClient.normalizeId("a".repeat(40)));
        for (String bad : List.of("", " ", "a".repeat(41), "rs_kills", "rs kills", "a/b", "../x", "rs.kills",
                "rsk\u00edlls", "x?y=1", "%2e%2e")) {
            assertNull(SiteClient.normalizeId(bad), bad);
            assertFalse(SiteClient.isValidId(bad), bad);
        }
        assertNull(SiteClient.normalizeId(null));
        assertFalse(SiteClient.isValidId("RsLib"));
        assertThrows(IllegalArgumentException.class, () -> site.release("../admin"));
        assertThrows(IllegalArgumentException.class, () -> SiteClient.downloadUrl("RsLib"));
        assertTrue(http.sent.isEmpty());
    }

    @Test
    void downloadUsesTheFixedAddress() throws Exception {
        assertEquals("https://rsdevelopment.dev/v1/releases/rslib/download", SiteClient.downloadUrl("rslib"));
        http.file(SiteClient.downloadUrl("rslib"), new byte[] {1, 2, 3});
        Path target = dir.resolve("RsLib.jar.part");
        assertEquals(3, site.download("rslib", target, 10));
        assertEquals(3, Files.size(target));
        FakeTransport.Sent sent = http.sent.get(0);
        assertEquals("application/java-archive", sent.headers().get("Accept"));
        assertEquals("RsLib/1.2.3", sent.headers().get("User-Agent"));
    }

    @Test
    void userAgentNamesTheRsLibVersion() {
        assertEquals("RsLib/1.0.0", SiteClient.userAgent(" 1.0.0 "));
        assertEquals("RsLib/unknown", SiteClient.userAgent(null));
    }
}
