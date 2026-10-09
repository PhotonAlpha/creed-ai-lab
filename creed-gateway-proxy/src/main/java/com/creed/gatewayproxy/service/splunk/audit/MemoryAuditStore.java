package com.creed.gatewayproxy.service.splunk.audit;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** The default store: the newest 500 rows, gone on restart. */
public class MemoryAuditStore implements SplunkAuditStore {

    static final int CAPACITY = 500;

    private final Deque<SplunkAuditRow> rows = new ArrayDeque<>();
    private long seq;

    @Override
    public void init() {
    }

    @Override
    public synchronized void save(SplunkAuditRow row) {
        rows.addFirst(row.toBuilder().id(++seq).createdAt(Instant.now()).build());
        while (rows.size() > CAPACITY) rows.removeLast();
    }

    @Override
    public synchronized List<SplunkAuditRow> list(int limit) {
        return rows.stream().limit(limit).toList();
    }
}
