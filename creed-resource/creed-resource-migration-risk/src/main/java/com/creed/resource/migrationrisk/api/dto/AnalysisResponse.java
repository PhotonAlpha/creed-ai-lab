package com.creed.resource.migrationrisk.api.dto;

import com.creed.resource.migrationrisk.domain.DetectedRefactoring;
import com.creed.resource.migrationrisk.domain.RiskItem;

import java.time.Instant;
import java.util.List;

/**
 * @param analysisId             the saved run's id; null when no release was given (nothing saved)
 * @param release                the release it was saved under
 * @param items                  riskiest first; each carries its {@code review} when saved
 * @param upstreamRefactorings   repo1@baseline -> repo1@HEAD (RefactoringMiner, changed files only)
 * @param migrationRefactorings  repo1@baseline -> repo2@HEAD over the same files: the old -> new mapping
 */
public record AnalysisResponse(
        Long analysisId,
        String release,
        Source source,
        Counts counts,
        List<RiskItem> items,
        List<DetectedRefactoring> upstreamRefactorings,
        List<DetectedRefactoring> migrationRefactorings,
        List<ChangedFileView> changedFiles,
        List<String> warnings,
        long durationMillis,
        Instant analyzedAt) {

    /**
     * @param mode         {@code GIT} or {@code DIRECTORY}
     * @param baselinePath the baseline folder (DIRECTORY mode); null in GIT mode, where the baseline is a commit
     * @param baseline     the resolved commits — null in DIRECTORY mode
     */
    public AnalysisResponse withSaved(long id, String releaseName, List<RiskItem> reviewed) {
        return new AnalysisResponse(id, releaseName, source, counts, reviewed, upstreamRefactorings,
                migrationRefactorings, changedFiles, warnings, durationMillis, analyzedAt);
    }

    public AnalysisResponse withoutItems() {
        return new AnalysisResponse(analysisId, release, source, counts, List.of(), upstreamRefactorings,
                migrationRefactorings, changedFiles, warnings, durationMillis, analyzedAt);
    }

    public record Source(String mode, String baselinePath, String repo1Path, String repo2Path,
                         CommitView baseline, CommitView repo1Head, CommitView repo2Head) {
    }

    public record CommitView(String revision, String id, String shortId, String message, String author, Instant time) {
    }

    public record Counts(int total, int high, int medium, int low) {
    }

    /** {@code kind}: JAVA, CONFIG (Spring config), POM, OTHER. */
    public record ChangedFileView(String change, String oldPath, String newPath, String kind, int addedLines) {
    }
}
