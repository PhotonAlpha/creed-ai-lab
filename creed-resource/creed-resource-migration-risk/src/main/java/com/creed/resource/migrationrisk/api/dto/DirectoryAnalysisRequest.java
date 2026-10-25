package com.creed.resource.migrationrisk.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Folder mode: three directories on the server, no git involved.
 *
 * @param baselinePath repo1 as it was when the migration started (or at the last sync) — a copy, an export,
 *                     an unpacked release; required, it is what separates migration changes from new ones
 * @param repo1Path    repo1 now
 * @param repo2Path    the migrated code base now
 * @param release      as in {@link AnalysisRequest}: saved under this release when given
 */
public record DirectoryAnalysisRequest(@NotBlank String baselinePath, @NotBlank String repo1Path,
                                       @NotBlank String repo2Path, @Size(max = 120) String release) {

    public DirectoryAnalysisRequest {
        release = release == null || release.isBlank() ? null : release.trim();
    }
}
