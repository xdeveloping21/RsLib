package com.rs.lib.input;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import com.rs.lib.Ph;
import com.rs.lib.Rs;
import com.rs.lib.chat.ChatEvents;
import com.rs.lib.chat.ChatLine;
import com.rs.lib.util.Amounts;

/**
 * Questions asked in chat ({@code rs().inputs()}): the player types one answer, which never
 * reaches the public chat, and the plugin gets exactly one {@link InputResult}.
 *
 * <pre>
 * rs().inputs().text(player, 3, 16).prompt("clan.ask-name").maxAttempts(3).open()
 *         .thenAccept(result -&gt; result.ifCompleted(name -&gt; createClan(player, name)));
 * </pre>
 *
 * <p>A player has one question at a time on the whole server: a new one from any plugin ends
 * the previous one as {@link InputStatus#REPLACED}. Questions end as
 * {@link InputStatus#LEFT} when the player leaves and as {@link InputStatus#SHUT_DOWN} when
 * the plugin shuts down, before its menus close.</p>
 *
 * <p>Messages (in the plugin's language files, or RsLib's English texts without them):
 * {@code rslib.input.cancel-hint}, {@code rslib.input.cancelled}, {@code rslib.input.timed-out},
 * {@code rslib.input.too-short}, {@code rslib.input.too-long}, {@code rslib.invalid-number},
 * {@code rslib.out-of-range}, {@code rslib.number-too-small} and {@code rslib.invalid-value}.
 * What the player typed goes into them as a literal {@code {value}}.</p>
 */
public final class Inputs {

    /** The longest text {@link #text(Player, int, int)} accepts (Minecraft's own chat limit). */
    public static final int MAX_TEXT_LENGTH = 256;

    static final String TOO_SHORT = "rslib.input.too-short";
    static final String TOO_LONG = "rslib.input.too-long";
    static final String INVALID_NUMBER = "rslib.invalid-number";
    static final String OUT_OF_RANGE = "rslib.out-of-range";
    static final String TOO_SMALL = "rslib.number-too-small";

    /** The open question of each player, across every plugin. */
    static final Map<UUID, InputSession<?>> ACTIVE = new ConcurrentHashMap<>();
    private static final Listener CHAT = new Listener() {
    };

    private final InputHost host;
    private final Set<InputSession<?>> mine = ConcurrentHashMap.newKeySet();
    private volatile boolean shutDown;

    public Inputs(Rs rs) {
        this(InputHost.of(Objects.requireNonNull(rs, "rs")));
        rs.onQuit(player -> left(player.getUniqueId()));
    }

    Inputs(InputHost host) {
        this.host = host;
    }

    /** The listener that owns the chat handler every plugin shares ({@link #registerChat}). */
    public static Listener chatListener() {
        return CHAT;
    }

    /**
     * Takes answers out of the chat; RsLib calls it once. The handler runs at LOWEST on the one
     * chat event of the server ({@link ChatEvents}), so an answer is read once and is gone before
     * the other listeners of that event (chat formatters) see it. A plugin that still listens to
     * the legacy event gets it first on Paper, so it may read the answer before it is cancelled.
     * It also takes answers another plugin already cancelled (a mute).
     */
    public static void registerChat(Plugin rslib) {
        listenChat(ChatEvents.registrar(rslib, CHAT));
    }

    static void listenChat(ChatEvents.Registrar registrar) {
        ChatEvents.listen(registrar, EventPriority.LOWEST, false, Inputs::onChat);
    }

    static void onChat(ChatLine line) {
        if (capture(line.speaker(), line.text())) {
            line.cancel();
        }
    }

    // ---------------------------------------------------------------- questions

    /** Any text from 1 to {@value #MAX_TEXT_LENGTH} characters. */
    public InputRequest<String> text(Player player) {
        return text(player, 1, MAX_TEXT_LENGTH);
    }

    /**
     * Text from {@code min} to {@code max} characters (code points; {@code max} is capped at
     * {@value #MAX_TEXT_LENGTH}).
     *
     * @throws IllegalArgumentException when {@code min} is negative or above {@code max}
     */
    public InputRequest<String> text(Player player, int min, int max) {
        if (min < 0 || max < min) {
            throw new IllegalArgumentException("Text length must be 0 <= min <= max, got " + min + ".." + max);
        }
        int cap = Math.min(max, MAX_TEXT_LENGTH);
        int floor = Math.min(min, cap);
        return custom(player, typed -> checkText(typed, floor, cap));
    }

    /**
     * An amount ({@code 2.5k}, {@code 10M}, {@code 1,500.25}; see {@link Amounts}) from
     * {@code min} to {@code max}; either bound may be null (no bound). A sign is accepted only
     * when {@code min} is negative.
     */
    public InputRequest<BigDecimal> amount(Player player, BigDecimal min, BigDecimal max) {
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException("min > max: " + min + " > " + max);
        }
        return custom(player, typed -> checkAmount(typed, min, max));
    }

    /** A whole number ({@code 64}, {@code 2k}) from {@code min} to {@code max}; fractions are refused. */
    public InputRequest<Long> whole(Player player, long min, long max) {
        if (min > max) {
            throw new IllegalArgumentException("min > max: " + min + " > " + max);
        }
        return custom(player, typed -> checkWhole(typed, min, max));
    }

    /** A value read by {@code parser}. */
    public <T> InputRequest<T> custom(Player player, InputParser<T> parser) {
        return new InputRequest<>(this, Objects.requireNonNull(player, "player"), Objects.requireNonNull(parser, "parser"));
    }

    /** Whether {@code player} has an open question from this plugin. */
    public boolean isWaiting(UUID player) {
        InputSession<?> session = player == null ? null : ACTIVE.get(player);
        return session != null && session.owner == this;
    }

    /** Ends the open question of {@code player} from this plugin as {@link InputStatus#CANCELLED}. */
    public void cancel(UUID player) {
        InputSession<?> session = player == null ? null : ACTIVE.get(player);
        if (session != null && session.owner == this) {
            session.end(InputStatus.CANCELLED, null);
        }
    }

    /** Ends every open question of this plugin as {@link InputStatus#SHUT_DOWN}; no new one opens after. */
    public void shutdown() {
        shutDown = true;
        for (InputSession<?> session : new ArrayList<>(mine)) {
            session.end(InputStatus.SHUT_DOWN, null);
        }
    }

    // ---------------------------------------------------------------- internals

    void requireOwnerThread(Player player) {
        if (!host.ownsHere(player)) {
            throw new IllegalStateException("Open chat questions on the player's thread (the main thread on Paper)");
        }
    }

    <T> CompletableFuture<InputResult<T>> open(InputRequest<T> request) {
        InputSession<T> session = new InputSession<>(this, host, request);
        if (shutDown || host.shuttingDown()) {
            session.end(InputStatus.SHUT_DOWN, null);
            return session.future;
        }
        if (!request.player().isOnline()) {
            session.end(InputStatus.LEFT, null);
            return session.future;
        }
        mine.add(session);
        InputSession<?> previous = ACTIVE.put(session.id, session);
        if (previous != null) {
            previous.end(InputStatus.REPLACED, null);
        }
        long ticks = Math.max(20L, (request.timeout().toMillis() + 49L) / 50L);
        try {
            session.timer(host.later(request.player(), ticks, () -> session.end(InputStatus.TIMED_OUT, null)));
        } catch (RuntimeException notScheduled) {
            // Without a timer the question could wait forever and swallow the player's chat.
            session.end(InputStatus.SHUT_DOWN, null);
            return session.future;
        }
        if (!session.isEnded()) {
            session.ask();
        }
        return session.future;
    }

    /** The player left: their question from this plugin ends as {@link InputStatus#LEFT}. */
    void left(UUID player) {
        InputSession<?> session = ACTIVE.get(player);
        if (session != null && session.owner == this) {
            session.end(InputStatus.LEFT, null);
        }
    }

    void forget(InputSession<?> session) {
        mine.remove(session);
    }

    /** Hands chat text to the player's open question; true when there is one (the chat is then cancelled). */
    static boolean capture(Player player, String text) {
        InputSession<?> session = ACTIVE.get(player.getUniqueId());
        if (session == null) {
            return false;
        }
        session.offer(text);
        return true;
    }

    static String checkText(String typed, int min, int max) throws InputRejected {
        int length = typed.codePointCount(0, typed.length());
        if (length < min) {
            throw new InputRejected(TOO_SHORT, Ph.of("min", min), Ph.of("max", max), Ph.literal("value", typed));
        }
        if (length > max) {
            throw new InputRejected(TOO_LONG, Ph.of("min", min), Ph.of("max", max), Ph.literal("value", typed));
        }
        return typed;
    }

    static BigDecimal checkAmount(String typed, BigDecimal min, BigDecimal max) throws InputRejected {
        Optional<BigDecimal> read = min != null && min.signum() < 0 ? Amounts.parseSigned(typed) : Amounts.parse(typed);
        if (read.isEmpty()) {
            throw new InputRejected(INVALID_NUMBER, Ph.literal("value", typed));
        }
        BigDecimal value = read.get();
        boolean low = min != null && value.compareTo(min) < 0;
        boolean high = max != null && value.compareTo(max) > 0;
        if (low && max == null) {
            throw new InputRejected(TOO_SMALL, Ph.of("min", plain(min)), Ph.literal("value", typed));
        }
        if (low || high) {
            throw new InputRejected(OUT_OF_RANGE, Ph.of("min", min == null ? "0" : plain(min)),
                    Ph.of("max", plain(max)), Ph.literal("value", typed));
        }
        return value;
    }

    static Long checkWhole(String typed, long min, long max) throws InputRejected {
        OptionalLong read = min < 0 ? Amounts.parseSignedWhole(typed) : Amounts.parseWhole(typed);
        if (read.isEmpty()) {
            throw new InputRejected(INVALID_NUMBER, Ph.literal("value", typed));
        }
        long value = read.getAsLong();
        if (value < min || value > max) {
            throw new InputRejected(OUT_OF_RANGE, Ph.of("min", min), Ph.of("max", max), Ph.literal("value", typed));
        }
        return value;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
