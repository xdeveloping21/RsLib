package com.rs.lib.scheduler;

/** A scheduled task that can be cancelled. Cancelling twice is harmless. */
public interface TaskHandle {

    void cancel();

    boolean isCancelled();
}
