package com.creed.resource.migrationrisk.domain;

/**
 * A place in one repository. {@code element} is the class / method as RefactoringMiner or the parser
 * names it; {@code line} is 1-based and null for whole-file locations. {@code repo} names which repo2 holds a
 * target location when repo2 is two repositories ({@code repo2A} / {@code repo2B}); null otherwise.
 */
public record Location(String path, Integer line, String element, String repo) {

    public Location(String path, Integer line, String element) {
        this(path, line, element, null);
    }

    public Location inRepo(String label) {
        return new Location(path, line, element, label);
    }
}
