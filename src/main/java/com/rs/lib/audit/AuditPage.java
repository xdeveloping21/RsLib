package com.rs.lib.audit;

import java.util.List;

/**
 * One page of entries, newest first.
 *
 * @param entries  the entries of the page
 * @param total    how many entries match the query in all
 * @param page     the page number, from 1
 * @param pageSize entries per page
 */
public record AuditPage(List<AuditEntry> entries, int total, int page, int pageSize) {

    public AuditPage {
        entries = List.copyOf(entries);
    }

    /** How many pages the matching entries fill; at least 1. */
    public int pages() {
        return Math.max(1, (total + pageSize - 1) / pageSize);
    }

    static AuditPage empty(AuditQuery query) {
        return new AuditPage(List.of(), 0, query.page(), query.pageSize());
    }
}
