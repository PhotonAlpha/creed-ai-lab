package com.creed.resource.migrationrisk.git;

import java.util.List;

/**
 * One file in {@code git diff baseline..head}, with rename detection.
 *
 * @param oldPath    null for an addition
 * @param newPath    null for a deletion
 * @param addedLines 1-based line numbers in the new version that the diff adds; empty for a deletion,
 *                   every line for an addition or a binary file's absence
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
