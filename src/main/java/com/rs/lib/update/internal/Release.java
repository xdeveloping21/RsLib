package com.rs.lib.update.internal;

/**
 * One GitHub release as RsLib keeps it: its tag and its web page (nothing else is stored).
 *
 * @param tag the {@code tag_name}
 * @param url the {@code html_url}, or null
 */
public record Release(String tag, String url) {
}
