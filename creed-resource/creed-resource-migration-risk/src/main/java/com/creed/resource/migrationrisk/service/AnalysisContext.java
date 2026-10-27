package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.git.ChangedFile;
import com.creed.resource.migrationrisk.source.SourceTree;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Everything one analysis reads: {@link Side#REPO1} (repo1 now) and {@link Side#REPO2} (repo2 now — repo2A + repo2B
 * as one tree), git commits or plain folders, the files that differ between them, and RefactoringMiner's
 * repo1 -> repo2 run. Files are read on demand, parsed at most once.
 */
public final class AnalysisContext {

    public enum Side { REPO1, REPO2 }

    private final SourceTree repo1;
    private final SourceTree repo2;
    private final List<ChangedFile> changes;
    private final RefactoringIndex refactorings;
    private final Progress progress;
    private final boolean skipTests;
    private final Map<String, Optional<String>> texts = new HashMap<>();
    private final Map<String, Optional<JavaStructure>> structures = new HashMap<>();

    public AnalysisContext(SourceTree repo1, SourceTree repo2, List<ChangedFile> changes,
                           RefactoringIndex refactorings, Progress progress, boolean skipTests) {
        this.repo1 = repo1;
        this.repo2 = repo2;
        this.changes = changes;
        this.refactorings = refactorings;
        this.progress = progress;
        this.skipTests = skipTests;
    }

    public Progress progress() {
        return progress;
    }

    /** Test code was left out of {@link #changes()}; whole-tree readers (the config index) must skip it too. */
    public boolean skipTests() {
        return skipTests;
    }

    /** Every file of one tree — for the readers that look past the change set (config keys, file extensions). */
    public Set<String> paths(Side side) {
        return tree(side).paths();
    }

    /** {@code diff(repo2, repo1)}: old = repo2's path, new = repo1's. */
    public List<ChangedFile> changes() {
        return changes;
    }

    /** repo1 -> repo2: where repo2 has repo1's classes and methods when not at the same place. */
    public RefactoringIndex refactorings() {
        return refactorings;
    }

    public Optional<String> text(Side side, String path) {
        if (path == null) {
            return Optional.empty();
        }
        return texts.computeIfAbsent(side + ":" + path, k -> tree(side).read(path));
    }

    public Optional<JavaStructure> structure(Side side, String path) {
        if (path == null) {
            return Optional.empty();
        }
        return structures.computeIfAbsent(side + ":" + path, k -> text(side, path).map(JavaStructure::parse));
    }

    /** Equal ids = byte-identical content (git blob ids in both kinds of tree). */
    public Optional<String> contentId(Side side, String path) {
        return path == null ? Optional.empty() : tree(side).contentId(path);
    }

    /** repo2 paths whose file name is {@code fileName}, for finding a class or file the migration moved. */
    public List<String> repo2PathsNamed(String fileName) {
        return repo2.pathsByFileName().getOrDefault(fileName, List.of());
    }

    private SourceTree tree(Side side) {
        return side == Side.REPO1 ? repo1 : repo2;
    }
}
