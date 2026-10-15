package com.rs.lib.input;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * How a chat question ended and, when it was answered, the answer.
 *
 * @param status how it ended
 * @param value  the accepted answer; null unless {@code status} is {@link InputStatus#COMPLETED}
 */
public record InputResult<T>(InputStatus status, T value) {

    public boolean isCompleted() {
        return status == InputStatus.COMPLETED;
    }

    /** Runs {@code action} with the answer when there is one. */
    public void ifCompleted(Consumer<? super T> action) {
        if (isCompleted()) {
            action.accept(value);
        }
    }

    /** The answer, or empty. */
    public Optional<T> answer() {
        return isCompleted() ? Optional.ofNullable(value) : Optional.empty();
    }
}
