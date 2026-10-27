package com.creed.resource.migrationrisk.api.dto;

import com.creed.resource.migrationrisk.api.dto.AnalysisResponse.CommitView;

import java.util.List;

/**
 * One repository's refs, for the branch pickers.
 *
 * @param currentBranch the checked-out branch; null on a detached HEAD
 * @param head          what {@code head} resolved to — recent commits are counted back from here
 */
public record RefsView(String currentBranch, CommitView head, List<String> branches, List<String> remoteBranches,
                       List<String> tags, List<CommitView> recentCommits) {
}
