package com.creed.resource.migrationrisk.api.dto;

import java.util.List;

/** {@code demo} is null when the demo repositories are disabled. */
/**
 * {@code jiraMock}: issues are made up in memory; {@code jiraBrowseUrl}: prefix for issue links (null with the mock);
 * {@code allowedHosts}: hosts whose https:// git URLs are accepted as repositories.
 */
public record MigrationRiskSettings(Demo demo, List<String> allowedRoots, List<Rule> apiRules, int maxChangedFiles,
                                    boolean jiraMock, String jiraBrowseUrl, List<String> allowedHosts) {

    /**
     * The git pair (repo1/repo2), repo2B (the part of the migration that went into a second repository), and the
     * same versions as plain folders.
     */
    public record Demo(String repo1Path, String repo1Head, String repo2Path, String repo2Head, String repo2bPath,
                       String repo1Folder, String repo2Folder, String repo2bFolder) {
    }

    public record Rule(String id, String pattern, String hint) {
    }
}
