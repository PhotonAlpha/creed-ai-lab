package com.creed.resource.migrationrisk.git;

import java.util.List;

/**
 * One file that differs between two trees ({@link com.creed.resource.migrationrisk.source.TreeDiff}). The analysis
 * diffs repo2 -> repo1, so {@code old} is repo2's side and {@code new} is repo1's.
 *
 * @param oldPath    repo2's path; null when only repo1 has the file ({@code ADDED})
 * @param newPath    repo1's path; null when only repo2 has it ({@code DELETED})
 * @param addedLines 1-based line numbers of repo1's file that repo2's lacks; empty when only repo2 has the file,
 *                   every line when only repo1 has it
 */
public record ChangedFile(Change change, String oldPath, String newPath, List<Integer> addedLines) {

    public enum Change {
        ADDED, MODIFIED, DELETED, RENAMED
    }

    public ChangedFile {
        addedLines = addedLines == null ? List.of() : List.copyOf(addedLines);
    }

    /** The path a reader recognises the file by: the new one, or the old one for a deletion. */
    public String path() {
        return newPath != null ? newPath : oldPath;
    }

    public boolean isJava() {
        return path().endsWith(".java");
    }
}
