package com.creed.resource.migrationrisk.api.dto;

import com.creed.resource.migrationrisk.api.dto.AnalysisResponse.CommitView;

import java.util.List;

/**
 * One repository's refs, for the branch and baseline pickers.
 *
 * @param currentBranch the checked-out branch; null on a detached HEAD
 * @param head          what {@code head} resolved to — recent commits are counted back from here
 */
public record RefsView(String currentBranch, CommitView head, List<String> branches, List<String> remoteBranches,
                       List<String> tags, List<CommitView> recentCommits) {

    /**
     * @param baseline null when {@code reason} is {@code NO_SHARED_HISTORY}
     * @param reason   {@code MERGE_BASE} (newest commit both heads descend from) or {@code NO_SHARED_HISTORY}
     */
    public record Suggestion(CommitView baseline, String reason) {
    }
}
