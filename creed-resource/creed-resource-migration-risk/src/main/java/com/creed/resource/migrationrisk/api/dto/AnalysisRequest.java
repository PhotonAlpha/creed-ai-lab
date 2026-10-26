package com.creed.resource.migrationrisk.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param repo1Path  the repository the other team keeps committing to (a path on the server)
 * @param baseline   the repo1 commit the migration started from — or last synced at; any revision git accepts
 * @param repo1Head  defaults to {@code HEAD}
 * @param repo2Path  the migrated repository
 * @param repo2Head  defaults to {@code HEAD}
 * @param repo2bPath optional second migrated repository (repo2B): when the migration split repo1 in two, repo2A +
 *                   repo2B are compared with repo1 as one code base
 * @param repo2bHead defaults to {@code HEAD}
 * @param skipTests  leave test code and test resources out of the comparison
 * @param release    when given, the result is saved under this release (created on first use) for review;
 *                   blank = a throwaway analysis, nothing written
 */
public record AnalysisRequest(
        @NotBlank String repo1Path,
        @NotBlank String baseline,
        String repo1Head,
        @NotBlank String repo2Path,
        String repo2Head,
        String repo2bPath,
        String repo2bHead,
        boolean skipTests,
        @Size(max = 120) String release) {

    public AnalysisRequest {
        repo1Head = repo1Head == null || repo1Head.isBlank() ? "HEAD" : repo1Head.trim();
        repo2Head = repo2Head == null || repo2Head.isBlank() ? "HEAD" : repo2Head.trim();
        repo2bPath = repo2bPath == null || repo2bPath.isBlank() ? null : repo2bPath.trim();
        repo2bHead = repo2bPath == null ? null : repo2bHead == null || repo2bHead.isBlank() ? "HEAD" : repo2bHead.trim();
        baseline = baseline == null ? null : baseline.trim();
        release = release == null || release.isBlank() ? null : release.trim();
    }
}
