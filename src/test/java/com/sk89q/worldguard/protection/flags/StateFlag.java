package com.sk89q.worldguard.protection.flags;

/** Test stand-in for a WorldGuard state flag. */
public final class StateFlag {

    public enum State {
        ALLOW,
        DENY
    }

    private final String name;

    public StateFlag(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
