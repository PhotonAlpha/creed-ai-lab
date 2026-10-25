package com.creed.resource.migrationrisk.domain;

import java.util.List;

/**
 * A RefactoringMiner refactoring reduced to plain data, so nothing outside {@code RefactoringDetector} sees
 * its API.
 *
 * @param structural moves, renames, extractions of classes and methods — the ones that change <em>where</em>
 *                   code lives. The rest (variable renames, type changes, annotations ...) are detail the
 *                   migration produces by the hundred; the UI hides them by default.
 */
public record DetectedRefactoring(String type, String description, boolean structural,
                                  List<CodeLocation> before, List<CodeLocation> after) {

    public DetectedRefactoring {
        before = List.copyOf(before);
        after = List.copyOf(after);
    }
}
