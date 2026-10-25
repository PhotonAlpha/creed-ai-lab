package com.creed.resource.migrationrisk.api.dto;

import java.util.List;

/** {@code demo} is null when the demo repositories are disabled. */
public record MigrationRiskSettings(Demo demo, List<String> allowedRoots, List<Rule> apiRules, int maxChangedFiles) {

    /** The git pair (repo1/repo2 + baseline tag) and the same three versions as plain folders. */
    public record Demo(String repo1Path, String baseline, String repo1Head, String repo2Path, String repo2Head,
                       String baselineFolder, String repo1Folder, String repo2Folder) {
    }

    public record Rule(String id, String pattern, String hint) {
    }
}
