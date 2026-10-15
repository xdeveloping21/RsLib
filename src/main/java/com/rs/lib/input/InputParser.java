package com.rs.lib.input;

/**
 * Turns what the player typed (trimmed, never a cancel word) into a value. Throw
 * {@link InputRejected} to tell the player what is wrong and keep waiting; a null value is
 * rejected with {@code rslib.invalid-value}. Runs on the player's thread.
 */
@FunctionalInterface
public interface InputParser<T> {

    T parse(String typed) throws InputRejected;
}
