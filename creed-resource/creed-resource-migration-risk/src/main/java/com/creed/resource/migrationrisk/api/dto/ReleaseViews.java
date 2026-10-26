package com.creed.resource.migrationrisk.api.dto;

import com.creed.resource.migrationrisk.domain.ReviewStatus;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Release, saved-run and review payloads. */
public final class ReleaseViews {

    private ReleaseViews() {
    }

    /** {@code latest} is null for a release with no saved run yet. */
    public record ReleaseView(long id, String name, String description, Instant createdAt, int analysisCount,
                              AnalysisSummary latest) {
    }

    /** A saved run without its items, for history lists. {@code repo2bPath}: null unless repo2 was two repositories. */
    public record AnalysisSummary(long id, String release, String mode, String repo1Path, String repo2Path, String repo2bPath,
                                  String baselineRef, AnalysisResponse.Counts counts, ReviewProgress review,
                                  long durationMillis, Instant analyzedAt) {
    }

    public record ReviewProgress(int pending, int safe, int attention) {
    }

    /** One item's new review; {@code note} optional, {@code reviewer} free text (there are no accounts). */
    public record ReviewRequest(@NotNull ReviewStatus status, @Size(max = 2000) String note,
                                @Size(max = 120) String reviewer) {
    }

    public record BatchReviewRequest(@NotEmpty List<Long> itemIds, @NotNull ReviewStatus status,
                                     @Size(max = 2000) String note, @Size(max = 120) String reviewer) {
    }
}
