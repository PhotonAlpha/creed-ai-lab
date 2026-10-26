package com.creed.resource.migrationrisk.domain;

import java.time.Instant;
import java.util.List;

/**
 * The review state of one saved item.
 *
 * @param itemId          the stored item's id — what the review endpoints take
 * @param carriedOverFrom the item of an earlier run of the same release whose verdict was copied, because
 *                        the item is unchanged since; null when reviewed (or pending) in this run
 * @param auto            set by the analysis, not a person: SYNCED because repo2 already matches repo1 HEAD. Never
 *                        carried over — the next run decides again
 * @param delta           against the release's previous run: NEW (not in it), CHANGED (different content),
 *                        SAME; null for a release's first run
 * @param previous        the verdict this file had before its content changed (it is PENDING again); null otherwise
 * @param changedParts    with {@code previous}: ids of the parts that are new or different since that verdict
 * @param jiraKey         the Jira issue created for this item, if any
 */
public record Review(long itemId, ReviewStatus status, String note, String reviewer, Instant reviewedAt,
                     Long carriedOverFrom, boolean auto, Delta delta, Previous previous, List<String> changedParts,
                     String jiraKey) {

    public Review {
        changedParts = changedParts == null ? List.of() : List.copyOf(changedParts);
    }

    public enum Delta { NEW, CHANGED, SAME }

    public record Previous(ReviewStatus status, String note, String reviewer) {
    }
}
