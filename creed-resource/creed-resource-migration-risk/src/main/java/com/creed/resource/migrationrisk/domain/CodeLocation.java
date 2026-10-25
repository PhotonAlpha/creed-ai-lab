package com.creed.resource.migrationrisk.domain;

/**
 * One side of a refactoring, as RefactoringMiner reports it: repository-relative path, 1-based line range,
 * the element type ({@code METHOD_DECLARATION}, {@code TYPE_DECLARATION} ...) and its text
 * ({@code public handle(orderId String) : String}, or a class's qualified name).
 */
public record CodeLocation(String path, int startLine, int endLine, String kind, String element) {

    public boolean overlaps(String otherPath, int otherStart, int otherEnd) {
        return path.equals(otherPath) && startLine <= otherEnd && otherStart <= endLine;
    }
}
