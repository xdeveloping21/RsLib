package com.rs.lib.update.internal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * The only door to the network of the update system: the server uses {@link JdkTransport},
 * tests a fake that answers with fixtures.
 */
public interface HttpTransport {

    /** A GET answered with any status (redirects are not followed); the body is read as UTF-8 text. */
    Response get(String url, Map<String, String> headers) throws IOException, InterruptedException;

    /**
     * Downloads {@code url} into {@code target} (replacing it); redirects are not followed.
     *
     * @return the number of bytes written
     * @throws IOException on a status other than 200 (a redirect included), or when the body
     *                     exceeds {@code maxBytes}
     */
    long download(String url, Map<String, String> headers, Path target, long maxBytes)
            throws IOException, InterruptedException;

    /** A status code and its body. */
    record Response(int status, String body) {
    }
}
