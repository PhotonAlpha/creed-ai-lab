package com.creed.resource.migrationrisk.domain;

/**
 * A place in one repository. {@code element} is the class / method as RefactoringMiner or the parser
 * names it; {@code line} is 1-based and null for whole-file locations.
 */
public record Location(String path, Integer line, String element) {
}
