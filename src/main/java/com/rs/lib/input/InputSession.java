package com.rs.lib.input;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import org.bukkit.entity.Player;

import com.rs.lib.Ph;
import com.rs.lib.scheduler.TaskHandle;

/**
 * One open chat question. It ends exactly once ({@link #end}): it leaves the registry, its
 * timer is cancelled, the player is told when it was cancelled or timed out (unless silent),
 * and the future completes on the player's thread.
 */
final class InputSession<T> {

    static final String CANCEL_HINT = "rslib.input.cancel-hint";
    static final String CANCELLED = "rslib.input.cancelled";
    static final String TIMED_OUT = "rslib.input.timed-out";
    static final String INVALID_VALUE = "rslib.invalid-value";

    final Inputs owner;
    final Player player;
    final UUID id;
    final CompletableFuture<InputResult<T>> future = new CompletableFuture<>();
    private final InputHost host;
    private final InputRequest<T> request;
    private final AtomicBoolean ended = new AtomicBoolean();
    private volatile TaskHandle timer;
    /** Rejected answers so far; touched only on the player's thread. */
    private int attempts;

    InputSession(Inputs owner, InputHost host, InputRequest<T> request) {
        this.owner = owner;
        this.host = host;
        this.request = request;
        this.player = request.player();
        this.id = player.getUniqueId();
    }

    void timer(TaskHandle timer) {
        this.timer = timer;
        if (ended.get()) {
            timer.cancel();
        }
    }

    /** Sends the prompt and, when there are cancel words, the hint that names them. */
    void ask() {
        if (request.promptKey() != null) {
            host.send(player, request.promptKey(), request.promptPhs());
        } else if (request.promptText() != null) {
            host.sendText(player, request.promptText());
        }
        List<String> words = request.cancelWordList();
        if (!words.isEmpty()) {
            host.send(player, CANCEL_HINT, Ph.literal("words", String.join(", ", words)));
        }
    }

    /** What the player typed, from the chat thread: handled on the player's thread. */
    void offer(String typed) {
        if (ended.get()) {
            return;
        }
        try {
            host.runFor(player, () -> answer(typed));
        } catch (RuntimeException notScheduled) {
            end(InputStatus.SHUT_DOWN, null);
        }
    }

    /** Checks one answer on the player's thread. */
    void answer(String raw) {
        if (ended.get()) {
            return;
        }
        String typed = raw == null ? "" : raw.trim();
        for (String word : request.cancelWordList()) {
            if (word.equalsIgnoreCase(typed)) {
                end(InputStatus.CANCELLED, null);
                return;
            }
        }
        T value;
        try {
            value = request.parser().parse(typed);
        } catch (InputRejected rejected) {
            reject(rejected.langKey(), rejected.placeholders());
            return;
        } catch (RuntimeException failure) {
            host.logger().log(Level.SEVERE, "The answer check of a chat question failed for " + player.getName()
                    + "; the question is cancelled.", failure);
            end(InputStatus.CANCELLED, null);
            return;
        }
        if (value == null) {
            reject(INVALID_VALUE, Ph.literal("value", typed));
            return;
        }
        end(InputStatus.COMPLETED, value);
    }

    private void reject(String key, Ph... phs) {
        host.send(player, key, phs);
        attempts++;
        int max = request.maxAttempts();
        if (max > 0 && attempts >= max) {
            end(InputStatus.CANCELLED, null);
        }
    }

    boolean isEnded() {
        return ended.get();
    }

    /** Ends the question; false when it had already ended. */
    boolean end(InputStatus status, T value) {
        if (!ended.compareAndSet(false, true)) {
            return false;
        }
        Inputs.ACTIVE.remove(id, this);
        owner.forget(this);
        TaskHandle running = timer;
        if (running != null) {
            running.cancel();
        }
        if (!request.silent() && (status == InputStatus.CANCELLED || status == InputStatus.TIMED_OUT)) {
            try {
                if (player.isOnline()) {
                    host.send(player, status == InputStatus.CANCELLED ? CANCELLED : TIMED_OUT);
                }
            } catch (RuntimeException notSent) {
                host.logger().log(Level.WARNING, "Could not tell " + player.getName() + " how a question ended", notSent);
            }
        }
        complete(new InputResult<>(status, status == InputStatus.COMPLETED ? value : null));
        return true;
    }

    /**
     * Completes the future on the player's thread. A player who left, or a plugin shutting down,
     * has no next tick to wait for: those complete where they are.
     */
    private void complete(InputResult<T> result) {
        InputStatus status = result.status();
        if (status == InputStatus.LEFT || status == InputStatus.SHUT_DOWN || host.ownsHere(player)) {
            future.complete(result);
            return;
        }
        try {
            host.runFor(player, () -> future.complete(result));
        } catch (RuntimeException notScheduled) {
            future.complete(result);
        }
    }
}
