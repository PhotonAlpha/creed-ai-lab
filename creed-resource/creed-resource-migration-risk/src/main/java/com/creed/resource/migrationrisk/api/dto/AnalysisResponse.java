package com.creed.resource.migrationrisk.api.dto;

import com.creed.resource.migrationrisk.domain.DetectedRefactoring;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.creed.resource.migrationrisk.domain.RiskItem;

import java.time.Instant;
import java.util.List;

/**
 * @param analysisId             the saved run's id; null when no release was given (nothing saved)
 * @param release                the release it was saved under
 * @param items                  riskiest first; each carries its {@code review} when saved
 * @param refactorings  RefactoringMiner repo1 -> repo2 over the differing Java files: where repo2 moved, renamed or
 *                      split repo1's classes and methods
 */
@JsonIgnoreProperties(ignoreUnknown = true) // runs saved before 2026-10-28 carry upstream/migrationRefactorings
public record AnalysisResponse(
        Long analysisId,
        String release,
        Source source,
        Counts counts,
        List<RiskItem> items,
        List<DetectedRefactoring> refactorings,
        List<ChangedFileView> changedFiles,
        List<String> warnings,
        long durationMillis,
        Instant analyzedAt) {

    /**
     * @param mode         {@code GIT} or {@code DIRECTORY}
     * @param repo1Head    the resolved commits — null in DIRECTORY mode
     * @param repo2bPath   the second repo2 (repo2B), merged with repo2Path; null for one repo2
     * @param repo2bHead   its resolved commit (GIT mode with repo2B)
     * @param skipTests    test code was left out of the comparison
     */
    public AnalysisResponse withSaved(long id, String releaseName, List<RiskItem> reviewed) {
        return new AnalysisResponse(id, releaseName, source, counts, reviewed, refactorings, changedFiles, warnings,
                durationMillis, analyzedAt);
    }

    public AnalysisResponse withoutItems() {
        return new AnalysisResponse(analysisId, release, source, counts, List.of(), refactorings, changedFiles, warnings,
                durationMillis, analyzedAt);
    }

    @JsonIgnoreProperties(ignoreUnknown = true) // baseline / baselinePath before 2026-10-28
    public record Source(String mode, String repo1Path, String repo2Path, CommitView repo1Head, CommitView repo2Head,
                         String repo2bPath, CommitView repo2bHead, boolean skipTests) {
    }

    public record CommitView(String revision, String id, String shortId, String message, String author, Instant time) {
    }

    public record Counts(int total, int high, int medium, int low) {
    }

    /**
     * {@code change}: ADDED = only repo1 has the file, DELETED = only repo2, MODIFIED = both, RENAMED = identical at
     * another path; {@code oldPath} is repo2's, {@code newPath} repo1's. {@code kind}: JAVA, CONFIG (a properties /
     * yml file of the global key comparison), POM, OTHER. {@code addedLines}: repo1 lines repo2's file lacks.
     */
    public record ChangedFileView(String change, String oldPath, String newPath, String kind, int addedLines) {
    }
}
