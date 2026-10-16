package com.rs.lib.input;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.entity.Player;

import com.rs.lib.Ph;

/**
 * Several chat questions in a row ({@code rs().inputs().wizard(player)}), then a summary the
 * player confirms. Nothing is applied before that: {@link #onConfirm} runs once, only when the
 * player confirms.
 *
 * <pre>
 * rs().inputs().wizard(player)
 *         .text("id", 3, 16, "kits.ask-id")
 *         .whole("slots", 1, 54, "kits.ask-slots")
 *         .choice("period", List.of("daily", "weekly"), "kits.ask-period")
 *         .onConfirm(answers -&gt; createKit(answers.text("id"), answers.whole("slots"), answers.text("period")))
 *         .onEnd(end -&gt; kitMenu.open(player))
 *         .start();
 * </pre>
 *
 * <h2>What the player can do</h2>
 * <p>Each step is one chat question ({@link Inputs}): what they type is checked by that step and
 * asked again when it is refused. A back word ({@link #DEFAULT_BACK_WORDS}) goes back one step
 * and asks it again; on the first step it asks the first step again. A cancel word
 * ({@link InputRequest#DEFAULT_CANCEL_WORDS}) ends the wizard. After the last step the answers
 * are listed and the player types a confirm word ({@link #DEFAULT_CONFIRM_WORDS}), a back word
 * (to change the last answer) or a cancel word. Every question waits {@link #stepTimeout} at
 * most. What the player typed is always shown as plain text.</p>
 *
 * <h2>One ending</h2>
 * <p>Confirming, cancelling, a timeout, the player leaving, another question replacing the open
 * one and the plugin shutting down all go through one ending that runs once: the open question
 * is ended, the wizard is forgotten, {@link #onConfirm} runs (only for
 * {@link WizardEnd#CONFIRMED}) and then {@link #onEnd}. Endings that come later do nothing. Like
 * one question, a wizard holds the player's one chat question on the whole server: a question
 * from anywhere else, or another wizard, ends it as {@link WizardEnd#REPLACED}.</p>
 *
 * <p>Messages (plugin language files, or RsLib's English texts without them):
 * {@code rslib.wizard.step}, {@code rslib.wizard.options}, {@code rslib.wizard.back-hint},
 * {@code rslib.wizard.summary}, {@code rslib.wizard.summary-line} and
 * {@code rslib.wizard.confirm}, besides those of {@link Inputs}. Only chat is used: no anvil or
 * dialog window.</p>
 */
public final class Wizard {

    /** The words that go back one step unless {@link #backWords} says otherwise. */
    public static final List<String> DEFAULT_BACK_WORDS = List.of("back", "atras", "atrás");
    /** The words that confirm the summary unless {@link #confirmWords} says otherwise. */
    public static final List<String> DEFAULT_CONFIRM_WORDS = List.of("confirm", "confirmar");

    static final String STEP = "rslib.wizard.step";
    static final String OPTIONS = "rslib.wizard.options";
    static final String BACK_HINT = "rslib.wizard.back-hint";
    static final String SUMMARY = "rslib.wizard.summary";
    static final String SUMMARY_LINE = "rslib.wizard.summary-line";
    static final String CONFIRM = "rslib.wizard.confirm";

    /** What a step's check gives back when the player typed a back word. */
    private static final Object GO_BACK = new Object();
    /** What the summary's check gives back when the player typed a confirm word. */
    private static final Object CONFIRMED = new Object();

    private final Inputs inputs;
    private final InputHost host;
    private final Player player;
    private final UUID playerId;
    private final List<Step> steps = new ArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean ended = new AtomicBoolean();
    /** The answers of the steps before the current one; touched only on the player's thread. */
    private final Map<String, Object> answers = new LinkedHashMap<>();

    private Duration stepTimeout = InputRequest.DEFAULT_TIMEOUT;
    private List<String> cancelWords = InputRequest.DEFAULT_CANCEL_WORDS;
    private List<String> backWords = DEFAULT_BACK_WORDS;
    private List<String> confirmWords = DEFAULT_CONFIRM_WORDS;
    private BiConsumer<Player, WizardAnswers> summary;
    private Consumer<WizardAnswers> onConfirm;
    private Consumer<WizardEnd> onEnd;

    /** The step being asked; the number of steps while the summary waits. */
    private volatile int index;
    private volatile InputSession<Object> question;
    private volatile WizardEnd endReason;

    Wizard(Inputs inputs, InputHost host, Player player) {
        this.inputs = inputs;
        this.host = host;
        this.player = player;
        this.playerId = player.getUniqueId();
    }

    // ---------------------------------------------------------------- steps

    /** A step answered with text from {@code min} to {@code max} characters (see {@link Inputs#text(Player, int, int)}). */
    public Wizard text(String key, int min, int max, String promptKey, Ph... phs) {
        if (min < 0 || max < min) {
            throw new IllegalArgumentException("Text length must be 0 <= min <= max, got " + min + ".." + max);
        }
        int cap = Math.min(max, Inputs.MAX_TEXT_LENGTH);
        int floor = Math.min(min, cap);
        return add(key, typed -> Inputs.checkText(typed, floor, cap), List.of(), promptKey, phs);
    }

    /** A step answered with an amount from {@code min} to {@code max}, either may be null (see {@link Inputs#amount}). */
    public Wizard amount(String key, BigDecimal min, BigDecimal max, String promptKey, Ph... phs) {
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException("min > max: " + min + " > " + max);
        }
        return add(key, typed -> Inputs.checkAmount(typed, min, max), List.of(), promptKey, phs);
    }

    /** A step answered with a whole number from {@code min} to {@code max} (see {@link Inputs#whole}). */
    public Wizard whole(String key, long min, long max, String promptKey, Ph... phs) {
        if (min > max) {
            throw new IllegalArgumentException("min > max: " + min + " > " + max);
        }
        return add(key, typed -> Inputs.checkWhole(typed, min, max), List.of(), promptKey, phs);
    }

    /** A step answered with whatever {@code parser} accepts (see {@link Inputs#custom}). */
    public Wizard custom(String key, InputParser<?> parser, String promptKey, Ph... phs) {
        return add(key, Objects.requireNonNull(parser, "parser"), List.of(), promptKey, phs);
    }

    /**
     * A step answered with one of {@code options}: the option itself (any case) or its number
     * in the list, from 1. The answer is the option as written here. The options are listed to
     * the player after the prompt.
     *
     * @throws IllegalArgumentException when there are no options, one is blank or two are the same
     */
    public Wizard choice(String key, List<String> options, String promptKey, Ph... phs) {
        if (options == null || options.isEmpty()) {
            throw new IllegalArgumentException("Choice step '" + key + "' has no options");
        }
        List<String> clean = new ArrayList<>();
        for (String option : options) {
            if (option == null || option.isBlank()) {
                throw new IllegalArgumentException("Choice step '" + key + "' has a blank option");
            }
            String trimmed = option.trim();
            for (String known : clean) {
                if (known.equalsIgnoreCase(trimmed)) {
                    throw new IllegalArgumentException("Choice step '" + key + "' lists '" + trimmed + "' twice");
                }
            }
            clean.add(trimmed);
        }
        List<String> fixed = List.copyOf(clean);
        return add(key, typed -> choose(fixed, typed), fixed, promptKey, phs);
    }

    private Wizard add(String key, InputParser<?> parser, List<String> options, String promptKey, Ph[] phs) {
        requireNotStarted();
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A wizard step needs a key");
        }
        Objects.requireNonNull(promptKey, "promptKey");
        for (Step step : steps) {
            if (step.key().equals(key)) {
                throw new IllegalArgumentException("Two wizard steps use the key '" + key + "'");
            }
        }
        steps.add(new Step(key, parser, options, promptKey, phs == null ? new Ph[0] : phs.clone()));
        return this;
    }

    // ---------------------------------------------------------------- settings

    /** How long each question waits for an answer; at least one second, null for the default 60. */
    public Wizard stepTimeout(Duration timeout) {
        requireNotStarted();
        this.stepTimeout = timeout == null ? InputRequest.DEFAULT_TIMEOUT
                : timeout.compareTo(InputRequest.MIN_TIMEOUT) < 0 ? InputRequest.MIN_TIMEOUT : timeout;
        return this;
    }

    /** The words (any case) that cancel the wizard on any question; none at all is allowed. */
    public Wizard cancelWords(String... words) {
        requireNotStarted();
        this.cancelWords = words(words);
        return this;
    }

    /** The words (any case) that go back one step; none at all turns going back off. */
    public Wizard backWords(String... words) {
        requireNotStarted();
        this.backWords = words(words);
        return this;
    }

    /**
     * The words (any case) that confirm the summary.
     *
     * @throws IllegalArgumentException when there are none: the wizard could never apply anything
     */
    public Wizard confirmWords(String... words) {
        requireNotStarted();
        List<String> clean = words(words);
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("A wizard needs at least one confirm word");
        }
        this.confirmWords = clean;
        return this;
    }

    /**
     * Sends the summary itself instead of one {@code rslib.wizard.summary-line} per answer (the
     * {@code rslib.wizard.confirm} line still follows). Insert answers with {@link Ph#literal}.
     */
    public Wizard summary(BiConsumer<Player, WizardAnswers> printer) {
        requireNotStarted();
        this.summary = printer;
        return this;
    }

    /** What to apply, run once on the player's thread when they confirm, with every answer. */
    public Wizard onConfirm(Consumer<WizardAnswers> action) {
        requireNotStarted();
        this.onConfirm = action;
        return this;
    }

    /**
     * Runs once on every ending, after {@link #onConfirm} for a confirmed one: on the player's
     * thread, except for {@link WizardEnd#LEFT} and {@link WizardEnd#SHUT_DOWN}, which run where
     * they happen and while the player or the plugin is going away.
     */
    public Wizard onEnd(Consumer<WizardEnd> action) {
        requireNotStarted();
        this.onEnd = action;
        return this;
    }

    // ---------------------------------------------------------------- running

    /**
     * Asks the first step. Call it once, on the player's thread. A wizard that cannot ask (the
     * player is offline, the plugin is shutting down) ends at once.
     *
     * @throws IllegalStateException when it has no steps, was started already, or is called from another thread
     */
    public Wizard start() {
        inputs.requireOwnerThread(player);
        if (steps.isEmpty()) {
            throw new IllegalStateException("A wizard needs at least one step");
        }
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("This wizard was already started");
        }
        inputs.track(this);
        index = 0;
        ask();
        return this;
    }

    /** Ends the wizard as {@link WizardEnd#CANCELLED}; false when it had already ended. */
    public boolean cancel() {
        return end(WizardEnd.CANCELLED);
    }

    public Player player() {
        return player;
    }

    /** True once it ended, however it ended. */
    public boolean isEnded() {
        return ended.get();
    }

    /** How it ended, or null while it runs. */
    public WizardEnd endReason() {
        return endReason;
    }

    /** The step being asked, from 0; {@link #stepCount()} while the summary waits for a confirm. */
    public int step() {
        return index;
    }

    public int stepCount() {
        return steps.size();
    }

    /** The answers given so far (those of the steps before the current one). */
    public WizardAnswers answers() {
        synchronized (answers) {
            return new WizardAnswers(answers);
        }
    }

    // ---------------------------------------------------------------- internals

    UUID playerId() {
        return playerId;
    }

    /** Asks the current step, or the summary after the last one. */
    private void ask() {
        if (ended.get()) {
            return;
        }
        int at = index;
        boolean review = at >= steps.size();
        InputParser<Object> parser;
        try {
            if (review) {
                showSummary();
                host.send(player, CONFIRM, Ph.literal("confirm", String.join("/", confirmWords)),
                        Ph.literal("back", String.join("/", backWords)), Ph.literal("cancel", String.join("/", cancelWords)));
                parser = this::readConfirm;
            } else {
                Step step = steps.get(at);
                host.send(player, STEP, Ph.of("step", at + 1), Ph.of("steps", steps.size()));
                host.send(player, step.promptKey(), step.phs());
                if (!step.options().isEmpty()) {
                    host.send(player, OPTIONS, Ph.literal("options", String.join(", ", step.options())));
                }
                parser = typed -> matches(backWords, typed) ? GO_BACK : step.parser().parse(typed);
            }
        } catch (RuntimeException failure) {
            host.logger().log(Level.SEVERE, "A wizard step for " + player.getName() + " could not be asked; it is cancelled.",
                    failure);
            end(WizardEnd.CANCELLED);
            return;
        }
        InputSession<Object> session;
        try {
            session = inputs.custom(player, parser).timeout(stepTimeout)
                    .cancelWords(cancelWords.toArray(String[]::new)).openSession();
        } catch (RuntimeException failure) {
            host.logger().log(Level.SEVERE, "A wizard question for " + player.getName() + " could not open; it is cancelled.",
                    failure);
            end(WizardEnd.CANCELLED);
            return;
        }
        question = session;
        session.future.whenComplete((result, failure) -> answered(session, at, result));
        if (!review && at > 0 && !backWords.isEmpty() && question == session && !session.isEnded()) {
            host.send(player, BACK_HINT, Ph.literal("words", String.join(", ", backWords)));
        }
    }

    /** One question ended: moves on, goes back, or ends the wizard. */
    private void answered(InputSession<Object> session, int at, InputResult<Object> result) {
        if (ended.get() || question != session) {
            return;
        }
        question = null;
        if (result == null) {
            end(WizardEnd.CANCELLED);
            return;
        }
        if (result.status() != InputStatus.COMPLETED) {
            end(WizardEnd.of(result.status()));
            return;
        }
        Object value = result.value();
        if (value == CONFIRMED) {
            end(WizardEnd.CONFIRMED);
            return;
        }
        if (value == GO_BACK) {
            int previous = Math.max(0, at - 1);
            synchronized (answers) {
                answers.remove(steps.get(previous).key());
            }
            index = previous;
        } else {
            synchronized (answers) {
                answers.put(steps.get(at).key(), value);
            }
            index = at + 1;
        }
        ask();
    }

    /**
     * Ends the wizard once: ends its open question, forgets it, runs {@link #onConfirm} for a
     * confirmed one and then {@link #onEnd}. Each part runs even when another fails.
     *
     * @return false when it had already ended
     */
    boolean end(WizardEnd reason) {
        if (!ended.compareAndSet(false, true)) {
            return false;
        }
        endReason = reason;
        InputSession<Object> open = question;
        question = null;
        if (open != null) {
            try {
                open.end(reason.questionStatus(), null);
            } catch (RuntimeException failure) {
                host.logger().log(Level.WARNING, "Could not end the open wizard question of " + player.getName(), failure);
            }
        }
        try {
            inputs.forget(this);
        } catch (RuntimeException failure) {
            host.logger().log(Level.WARNING, "Could not forget the wizard of " + player.getName(), failure);
        }
        if (reason == WizardEnd.CONFIRMED && onConfirm != null) {
            try {
                onConfirm.accept(answers());
            } catch (RuntimeException failure) {
                host.logger().log(Level.SEVERE, "The confirm action of a wizard failed for " + player.getName(), failure);
            }
        }
        if (onEnd != null) {
            try {
                onEnd.accept(reason);
            } catch (RuntimeException failure) {
                host.logger().log(Level.SEVERE, "The end action of a wizard failed for " + player.getName(), failure);
            }
        }
        return true;
    }

    private void showSummary() {
        WizardAnswers all = answers();
        if (summary != null) {
            summary.accept(player, all);
            return;
        }
        host.send(player, SUMMARY);
        for (Map.Entry<String, Object> answer : all.asMap().entrySet()) {
            host.send(player, SUMMARY_LINE, Ph.literal("name", answer.getKey()), Ph.literal("value", display(answer.getValue())));
        }
    }

    private Object readConfirm(String typed) throws InputRejected {
        if (matches(confirmWords, typed)) {
            return CONFIRMED;
        }
        if (matches(backWords, typed)) {
            return GO_BACK;
        }
        throw new InputRejected(InputSession.INVALID_VALUE, Ph.literal("value", typed));
    }

    static String choose(List<String> options, String typed) throws InputRejected {
        for (String option : options) {
            if (option.equalsIgnoreCase(typed)) {
                return option;
            }
        }
        if (!typed.isEmpty() && typed.length() <= 4 && typed.chars().allMatch(c -> c >= '0' && c <= '9')) {
            int number = Integer.parseInt(typed);
            if (number >= 1 && number <= options.size()) {
                return options.get(number - 1);
            }
        }
        throw new InputRejected(InputSession.INVALID_VALUE, Ph.literal("value", typed));
    }

    static String display(Object value) {
        return value instanceof BigDecimal number ? number.stripTrailingZeros().toPlainString() : String.valueOf(value);
    }

    private static boolean matches(List<String> words, String typed) {
        for (String word : words) {
            if (word.equalsIgnoreCase(typed)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> words(String[] words) {
        List<String> clean = new ArrayList<>();
        if (words != null) {
            for (String word : words) {
                if (word != null && !word.isBlank()) {
                    clean.add(word.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return List.copyOf(clean);
    }

    private void requireNotStarted() {
        if (started.get()) {
            throw new IllegalStateException("A wizard cannot change after start()");
        }
    }

    @Override
    public String toString() {
        return "Wizard[" + player.getName() + " step " + index + "/" + steps.size()
                + (endReason == null ? "" : " " + endReason) + "]";
    }

    /** One question of the wizard. */
    private record Step(String key, InputParser<?> parser, List<String> options, String promptKey, Ph[] phs) {
    }
}
