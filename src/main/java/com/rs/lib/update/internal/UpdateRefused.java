package com.rs.lib.update.internal;

/** A self-update step found something it will not accept; the message says what. */
public final class UpdateRefused extends Exception {

    public UpdateRefused(String message) {
        super(message);
    }
}
