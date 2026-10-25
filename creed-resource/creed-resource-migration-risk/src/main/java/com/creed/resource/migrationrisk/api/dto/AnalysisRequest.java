package com.creed.resource.migrationrisk.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param repo1Path  the repository the other team keeps committing to (a path on the server)
 * @param baseline   the repo1 commit the migration started from — or last synced at; any revision git accepts
 * @param repo1Head  defaults to {@code HEAD}
 * @param repo2Path  the migrated repository
 * @param repo2Head  defaults to {@code HEAD}
 * @param release    when given, the result is saved under this release (created on first use) for review;
 *                   blank = a throwaway analysis, nothing written
 */
public record AnalysisRequest(
        @NotBlank String repo1Path,
        @NotBlank String baseline,
        String repo1Head,
        @NotBlank String repo2Path,
        String repo2Head,
        @Size(max = 120) String release) {

    public AnalysisRequest {
        repo1Head = repo1Head == null || repo1Head.isBlank() ? "HEAD" : repo1Head.trim();
        repo2Head = repo2Head == null || repo2Head.isBlank() ? "HEAD" : repo2Head.trim();
        baseline = baseline == null ? null : baseline.trim();
        release = release == null || release.isBlank() ? null : release.trim();
    }
}
