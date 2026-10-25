package com.creed.resource.migrationrisk.domain;

import java.time.Instant;

/**
 * The review state of one saved item.
 *
 * @param itemId          the stored item's id — what the review endpoints take
 * @param carriedOverFrom the item of an earlier run of the same release whose verdict was copied, because
 *                        the item is unchanged since; null when reviewed (or pending) in this run
 */
public record Review(long itemId, ReviewStatus status, String note, String reviewer, Instant reviewedAt,
                     Long carriedOverFrom) {
}
