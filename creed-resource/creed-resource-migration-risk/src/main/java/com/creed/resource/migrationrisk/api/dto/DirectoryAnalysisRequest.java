package com.creed.resource.migrationrisk.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Folder mode: directories on the server, no git involved.
 *
 * @param repo1Path    repo1 now
 * @param repo2Path    the migrated code base now
 * @param repo2bPath   optional second migrated folder, merged with repo2Path into one code base
 * @param skipTests    leave test code and test resources out of the comparison
 * @param release      as in {@link AnalysisRequest}: saved under this release when given
 */
public record DirectoryAnalysisRequest(@NotBlank String repo1Path,
                                       @NotBlank String repo2Path, String repo2bPath, boolean skipTests,
                                       @Size(max = 120) String release) {

    public DirectoryAnalysisRequest {
        repo2bPath = repo2bPath == null || repo2bPath.isBlank() ? null : repo2bPath.trim();
        release = release == null || release.isBlank() ? null : release.trim();
    }
}
