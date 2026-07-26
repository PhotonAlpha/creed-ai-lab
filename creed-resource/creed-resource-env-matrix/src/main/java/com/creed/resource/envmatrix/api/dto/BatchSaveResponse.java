package com.creed.resource.envmatrix.api.dto;

import java.util.List;

/**
 * Outcome of a batch save. On success the counts let the UI show "3 added, 5 updated, 1 removed";
 * on failure {@code issues} carries per-row messages and nothing was written (the whole save runs in
 * one transaction, so a validation failure on row 7 leaves rows 1-6 untouched).
 */
public record BatchSaveResponse(
        boolean success,
        int inserted,
        int updated,
        int deleted,
        List<Issue> issues,
        /** Conflicts present in the data *after* the save — a warning, not a rejection. */
        List<ConflictGroup> conflicts) {

    /**
     * A per-row problem.
     *
     * @param index zero-based position in the submitted {@code endpoints} list, so the UI can scroll
     *              to and highlight the offending row
     */
    public record Issue(int index, Long id, String field, String message) {
    }

    public static BatchSaveResponse rejected(List<Issue> issues) {
        return new BatchSaveResponse(false, 0, 0, 0, issues, List.of());
    }
}
