package com.rs.lib.action;

/**
 * What a physical item was used on, for the {@code [click-block]} and {@code [click-air]}
 * guards. Menus have no surface: in an {@link ActionContext} without one both guards skip
 * their line.
 */
public enum ClickSurface {
    BLOCK,
    AIR
}
