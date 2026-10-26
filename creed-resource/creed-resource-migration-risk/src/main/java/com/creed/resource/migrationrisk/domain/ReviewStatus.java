package com.creed.resource.migrationrisk.domain;

/** A reviewer's verdict on one item. */
public enum ReviewStatus {
    /** Not looked at yet. */
    PENDING,
    /** Expected — the difference is intended (part of the migration, or already handled). */
    SAFE,
    /** Not expected: needs special attention when syncing. */
    ATTENTION,
    /**
     * Done: repo2 has repo1 HEAD's state. Set automatically when an analysis finds the file in sync (review
     * {@code auto}), or by hand for what cannot be compared (a template re-written in another syntax).
     */
    SYNCED
}
