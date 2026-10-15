package com.rs.lib.action;

/**
 * What the page actions ({@code [next-page]}, {@code [previous-page]}, {@code [set-page]},
 * {@code [refresh-page]}, {@code [refresh-menu]}) act on: the open menu session of the click.
 * All five do nothing when {@link #paginationEnabled()} is false.
 */
public interface PageTarget {

    void nextPage();

    void previousPage();

    /** Jumps to {@code page}; the first page is 1. */
    void setPage(int page);

    /** Renders the content again. */
    void refreshPage();

    /** Renders everything again, title included. */
    void refreshMenu();

    /** Whether the menu declares {@code pagination: true}. */
    boolean paginationEnabled();
}
