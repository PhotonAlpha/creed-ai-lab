package com.creed.resource.migrationrisk.domain;

/** A reviewer's verdict on one item. */
public enum ReviewStatus {
    /** Not looked at yet. */
    PENDING,
    /** Expected — the difference is intended (part of the migration, or already handled). */
    SAFE,
    /** Not expected: needs special attention when syncing. */
    ATTENTION
}
