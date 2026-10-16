package com.rs.lib.input;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.entity.Player;

import com.rs.lib.Ph;

/**
 * One chat question, built with {@link Inputs} and asked with {@link #open()}.
 *
 * <pre>
 * rs().inputs().amount(player, BigDecimal.ONE, null)
 *         .prompt("bank.ask-deposit")
 *         .open()
 *         .thenAccept(result -&gt; result.ifCompleted(amount -&gt; deposit(player, amount)));
 * </pre>
 *
 * <p>Defaults: no prompt, 60 seconds, cancel words {@link #DEFAULT_CANCEL_WORDS}, unlimited
 * attempts, not silent. Settings made after {@link #open()} have no effect.</p>
 */
public final class InputRequest<T> {

    /** The words that cancel a question unless {@link #cancelWords} says otherwise. */
    public static final List<String> DEFAULT_CANCEL_WORDS = List.of("cancel", "cancelar");
    /** How long a question waits unless {@link #timeout} says otherwise. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    /** The shortest wait {@link #timeout} accepts. */
    public static final Duration MIN_TIMEOUT = Duration.ofSeconds(1);

    private final Inputs inputs;
    private final Player player;
    private final InputParser<T> parser;
    private final AtomicBoolean opened = new AtomicBoolean();
    private volatile String promptKey;
    private volatile Ph[] promptPhs = new Ph[0];
    private volatile String promptText;
    private volatile Duration timeout = DEFAULT_TIMEOUT;
    private volatile List<String> cancelWords = DEFAULT_CANCEL_WORDS;
    private volatile int maxAttempts;
    private volatile boolean silent;

    InputRequest(Inputs inputs, Player player, InputParser<T> parser) {
        this.inputs = inputs;
        this.player = player;
        this.parser = parser;
    }

    /** Asks with the message {@code langKey} of the plugin's language files. */
    public InputRequest<T> prompt(String langKey, Ph... phs) {
        this.promptKey = langKey;
        this.promptPhs = phs == null ? new Ph[0] : phs.clone();
        this.promptText = null;
        return this;
    }

    /** Asks with {@code text} as written (colors and tags rendered), not a message key. */
    public InputRequest<T> promptText(String text) {
        this.promptText = text;
        this.promptKey = null;
        return this;
    }

    /** How long to wait for an answer; at least one second, null for the default 60. */
    public InputRequest<T> timeout(Duration timeout) {
        this.timeout = timeout == null ? DEFAULT_TIMEOUT
                : timeout.compareTo(MIN_TIMEOUT) < 0 ? MIN_TIMEOUT : timeout;
        return this;
    }

    /** The words (any case) that cancel the question; none at all is allowed. */
    public InputRequest<T> cancelWords(String... words) {
        List<String> clean = new ArrayList<>();
        if (words != null) {
            for (String word : words) {
                if (word != null && !word.isBlank()) {
                    clean.add(word.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        this.cancelWords = List.copyOf(clean);
        return this;
    }

    /** Rejected answers allowed before the question ends as cancelled; 0 (the default) is unlimited. */
    public InputRequest<T> maxAttempts(int attempts) {
        this.maxAttempts = Math.max(0, attempts);
        return this;
    }

    /** With true, no "cancelled" or "timed out" message is sent; the prompt and hint still are. */
    public InputRequest<T> silent(boolean silent) {
        this.silent = silent;
        return this;
    }

    /**
     * Asks the question. Call it once, on the player's thread (the main thread on Paper). A
     * question this player already had, from any plugin, ends as {@link InputStatus#REPLACED}.
     * The future always completes, on the player's thread: answered, cancelled, timed out, the
     * player left, replaced, or the plugin shut down. While the plugin shuts down it completes
     * at once with {@link InputStatus#SHUT_DOWN}, so a callback cannot ask again then.
     *
     * @throws IllegalStateException when called twice or from another thread
     */
    public CompletableFuture<InputResult<T>> open() {
        return openSession().future;
    }

    /** {@link #open()}, handing back the session itself (the wizard ends it when it ends). */
    InputSession<T> openSession() {
        inputs.requireOwnerThread(player);
        if (!opened.compareAndSet(false, true)) {
            throw new IllegalStateException("This question was already opened");
        }
        return inputs.open(this);
    }

    Player player() {
        return player;
    }

    InputParser<T> parser() {
        return parser;
    }

    String promptKey() {
        return promptKey;
    }

    Ph[] promptPhs() {
        return promptPhs;
    }

    String promptText() {
        return promptText;
    }

    Duration timeout() {
        return timeout;
    }

    List<String> cancelWordList() {
        return cancelWords;
    }

    int maxAttempts() {
        return maxAttempts;
    }

    boolean silent() {
        return silent;
    }
}
