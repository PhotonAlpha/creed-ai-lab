package com.creed.resource.envmatrix.api.dto;

import java.util.List;

/**
 * Outcome of a topology save. All rows are validated before anything is written, so a non-empty
 * {@code issues} means nothing was saved at all.
 */
public record ReleaseTopologySaveResponse(
        boolean success,
        int nodesInserted,
        int nodesUpdated,
        int nodesDeleted,
        int linksInserted,
        int linksUpdated,
        int linksDeleted,
        List<Issue> issues) {

    /**
     * A per-row problem.
     *
     * @param section {@code "nodes"} or {@code "links"} — which list {@code index} refers to
     * @param index   zero-based position in that list, so the UI can point at the offending row
     */
    public record Issue(String section, int index, Long id, String field, String message) {
    }

    public static ReleaseTopologySaveResponse rejected(List<Issue> issues) {
        return new ReleaseTopologySaveResponse(false, 0, 0, 0, 0, 0, 0, issues);
    }
}
