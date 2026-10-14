package com.rs.lib.reload;

/** Something a plugin refreshes when it is reloaded. Config and messages are already re-read when it runs. */
@FunctionalInterface
public interface Reloadable {

    void reload();
}
