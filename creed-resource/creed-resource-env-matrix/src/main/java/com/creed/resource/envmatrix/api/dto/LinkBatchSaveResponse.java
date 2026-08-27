package com.creed.resource.envmatrix.api.dto;

import java.util.List;

/**
 * Outcome of a link batch save. All rows are validated before anything is written, so
 * {@code issues} being non-empty means nothing was saved at all.
 */
public record LinkBatchSaveResponse(
        boolean success,
        int inserted,
        int updated,
        int deleted,
        List<Issue> issues) {

    /**
     * A per-row problem.
     *
     * @param index zero-based position in the submitted {@code links} list, so the UI can point at
     *              the offending row
     */
    public record Issue(int index, Long id, String field, String message) {
    }

    public static LinkBatchSaveResponse rejected(List<Issue> issues) {
        return new LinkBatchSaveResponse(false, 0, 0, 0, issues);
    }
}
