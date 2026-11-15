package com.creed.gatewayproxy.service.splunk.audit;

import java.util.List;

/**
 * Where the broker's audit rows go. The audit is mandatory: a {@link #save} that throws fails the
 * request rather than let a session go unrecorded.
 */
public interface SplunkAuditStore extends AutoCloseable {

    /** Create the table if needed; a JDBC store that cannot connect fails startup here. */
    void init();

    void save(SplunkAuditRow row);

    /**
     * Newest first, for the page: rows whose event type starts with {@link #ADMIN_PREFIX} (the admin API)
     * are left out — they stay in the store, readable in the database.
     */
    List<SplunkAuditRow> list(int limit);

    String ADMIN_PREFIX = "ADMIN_";

    @Override
    default void close() {
    }
}
