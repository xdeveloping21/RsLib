package com.rs.lib.chat;

/** An interceptor or rule registered with {@code rs().chat()}; {@link #close()} removes it. */
public final class ChatHook {

    private final Runnable remover;
    private volatile boolean open = true;

    ChatHook(Runnable remover) {
        this.remover = remover;
    }

    /** Stops it; safe to call more than once. The plugin's shutdown closes it too. */
    public void close() {
        if (open) {
            open = false;
            remover.run();
        }
    }

    public boolean isOpen() {
        return open;
    }
}
