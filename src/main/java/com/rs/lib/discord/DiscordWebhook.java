package com.rs.lib.discord;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.rs.lib.Rs;

/**
 * Messages and embeds to Discord webhooks, reached with {@code rs().discord()}. No external
 * library: the JDK's HTTP client on one background thread per plugin.
 *
 * <pre>
 * rs().discord().send(config.getString("webhook"), "Server restarting");
 * rs().discord().message(url).username("Auctions")
 *         .embed(rs().discord().embed().title("New auction").description(text).color(0xF1C40F).timestampNow())
 *         .send();
 * </pre>
 *
 * <ul>
 *   <li>Fire and forget: {@code send()} only queues and returns at once, on any thread; the
 *   caller is never told whether Discord took the message. Messages go out in order.</li>
 *   <li>A message with no content and no embed is not sent. Up to 10 embeds and 25 fields per
 *   embed are kept; the rest are ignored. Text longer than Discord accepts is cut to its limit
 *   (content 2000, username 80, title 256, description 4096, field name 256 and value 1024,
 *   footer 2048, author 256 characters), and an empty field name or value becomes a
 *   zero-width space, so Discord does not refuse the whole message.</li>
 *   <li>Rate limits are respected: see {@link WebhookQueue}. Failures are reported once per
 *   webhook, and logs never show a webhook's token.</li>
 *   <li>Webhook addresses live in each plugin's config.</li>
 * </ul>
 *
 * <p>When the plugin shuts down, {@link #drain()} sends what is still queued for up to three
 * seconds and reports how many were left.</p>
 */
public final class DiscordWebhook {

    /** How long {@link #drain()} may take. */
    static final Duration DRAIN_BUDGET = Duration.ofSeconds(3);
    static final int MAX_EMBEDS = 10;
    static final int MAX_FIELDS = 25;
    static final int MAX_CONTENT = 2000;
    static final int MAX_USERNAME = 80;
    static final int MAX_TITLE = 256;
    static final int MAX_DESCRIPTION = 4096;
    static final int MAX_FIELD_NAME = 256;
    static final int MAX_FIELD_VALUE = 1024;
    static final int MAX_FOOTER = 2048;
    static final int MAX_AUTHOR = 256;

    private final WebhookQueue queue;

    /** Created by the context. */
    public DiscordWebhook(Rs rs) {
        this(new WebhookQueue(rs.plugin().getLogger(), rs.plugin().getName(), WebhookTransport.Jdk::new, System::nanoTime));
    }

    DiscordWebhook(WebhookQueue queue) {
        this.queue = queue;
    }

    /** A message to {@code webhookUrl}; nothing is sent until {@link Message#send()}. */
    public Message message(String webhookUrl) {
        return new Message(webhookUrl);
    }

    /** Sends plain text to {@code webhookUrl}. */
    public void send(String webhookUrl, String content) {
        message(webhookUrl).content(content).send();
    }

    /** A new embed, to add to a message with {@link Message#embed(Embed)}. */
    public Embed embed() {
        return new Embed();
    }

    /**
     * Sends what is still queued, for up to three seconds, then stops the worker. Called by the
     * context on shutdown; messages sent afterwards are dropped.
     */
    public void drain() {
        queue.drain(DRAIN_BUDGET);
    }

    /** A webhook message. Not thread-safe while being built; {@link #send()} copies it. */
    public final class Message {

        private final String url;
        private String content;
        private String username;
        private String avatarUrl;
        private final List<Embed> embeds = new ArrayList<>(1);

        private Message(String url) {
            this.url = url;
        }

        public Message content(String content) {
            this.content = content;
            return this;
        }

        /** Overrides the webhook's name for this message. */
        public Message username(String username) {
            this.username = username;
            return this;
        }

        /** Overrides the webhook's avatar for this message. */
        public Message avatarUrl(String avatarUrl) {
            this.avatarUrl = avatarUrl;
            return this;
        }

        /** Adds an embed; past the tenth they are ignored. */
        public Message embed(Embed embed) {
            if (embed != null && embeds.size() < MAX_EMBEDS) {
                embeds.add(embed);
            }
            return this;
        }

        /** Queues the message as it is now; an empty one (no content, no embed) is dropped. */
        public void send() {
            if ((content == null || content.isBlank()) && embeds.isEmpty()) {
                return;
            }
            queue.offer(url, toJson());
        }

        String toJson() {
            StringBuilder out = new StringBuilder(128);
            out.append('{');
            boolean first = Json.field(out, true, "content", Json.cut(content, MAX_CONTENT));
            first = Json.field(out, first, "username", Json.cut(username, MAX_USERNAME));
            first = Json.field(out, first, "avatar_url", avatarUrl);
            if (!embeds.isEmpty()) {
                if (!first) {
                    out.append(',');
                }
                out.append("\"embeds\":[");
                for (int i = 0; i < embeds.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    embeds.get(i).write(out);
                }
                out.append(']');
            }
            return out.append('}').toString();
        }
    }

    /** A rich embed. Every part is optional; null leaves it out. */
    public static final class Embed {

        private String title;
        private String url;
        private String description;
        private Integer color;
        private String footer;
        private String footerIcon;
        private String timestamp;
        private String authorName;
        private String authorUrl;
        private String authorIcon;
        private String thumbnail;
        private String image;
        private final List<Field> fields = new ArrayList<>(0);

        Embed() {
        }

        public Embed title(String title) {
            this.title = title;
            return this;
        }

        public Embed description(String description) {
            this.description = description;
            return this;
        }

        /** {@code 0xRRGGBB}; higher bits are ignored. */
        public Embed color(int rgb) {
            this.color = rgb & 0xFFFFFF;
            return this;
        }

        /** Adds a field; past the 25th they are ignored. */
        public Embed field(String name, String value, boolean inline) {
            if (fields.size() < MAX_FIELDS) {
                fields.add(new Field(name, value, inline));
            }
            return this;
        }

        public Embed footer(String text) {
            return footer(text, null);
        }

        public Embed footer(String text, String iconUrl) {
            this.footer = text;
            this.footerIcon = iconUrl;
            return this;
        }

        /** The link of the title. */
        public Embed url(String url) {
            this.url = url;
            return this;
        }

        public Embed author(String name, String url, String iconUrl) {
            this.authorName = name;
            this.authorUrl = url;
            this.authorIcon = iconUrl;
            return this;
        }

        public Embed thumbnail(String url) {
            this.thumbnail = url;
            return this;
        }

        public Embed image(String url) {
            this.image = url;
            return this;
        }

        /** Stamps the embed with the current time (ISO-8601, UTC). */
        public Embed timestampNow() {
            this.timestamp = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
            return this;
        }

        void write(StringBuilder out) {
            out.append('{');
            boolean first = Json.field(out, true, "title", Json.cut(title, MAX_TITLE));
            first = Json.field(out, first, "url", url);
            first = Json.field(out, first, "description", Json.cut(description, MAX_DESCRIPTION));
            if (color != null) {
                out.append(first ? "" : ",").append("\"color\":").append(color.intValue());
                first = false;
            }
            if (footer != null) {
                out.append(first ? "" : ",").append("\"footer\":{");
                Json.field(out, Json.field(out, true, "text", Json.cut(footer, MAX_FOOTER)), "icon_url", footerIcon);
                out.append('}');
                first = false;
            }
            first = Json.field(out, first, "timestamp", timestamp);
            if (authorName != null) {
                out.append(first ? "" : ",").append("\"author\":{");
                boolean inner = Json.field(out, true, "name", Json.cut(authorName, MAX_AUTHOR));
                inner = Json.field(out, inner, "url", authorUrl);
                Json.field(out, inner, "icon_url", authorIcon);
                out.append('}');
                first = false;
            }
            first = object(out, first, "thumbnail", thumbnail);
            first = object(out, first, "image", image);
            if (!fields.isEmpty()) {
                out.append(first ? "" : ",").append("\"fields\":[");
                for (int i = 0; i < fields.size(); i++) {
                    Field field = fields.get(i);
                    out.append(i > 0 ? ",{" : "{");
                    Json.field(out, true, "name", Json.nonEmpty(Json.cut(field.name(), MAX_FIELD_NAME)));
                    Json.field(out, false, "value", Json.nonEmpty(Json.cut(field.value(), MAX_FIELD_VALUE)));
                    out.append(",\"inline\":").append(field.inline()).append('}');
                }
                out.append(']');
            }
            out.append('}');
        }

        private record Field(String name, String value, boolean inline) {
        }

        /** {@code "name":{"url":...}} when {@code url} is not null. */
        private static boolean object(StringBuilder out, boolean first, String name, String url) {
            if (url == null) {
                return first;
            }
            out.append(first ? "" : ",").append('"').append(name).append("\":{");
            Json.field(out, true, "url", url);
            out.append('}');
            return false;
        }
    }
}
