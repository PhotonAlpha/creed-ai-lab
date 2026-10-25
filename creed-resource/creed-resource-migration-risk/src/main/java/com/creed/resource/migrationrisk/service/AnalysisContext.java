package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.git.ChangedFile;
import com.creed.resource.migrationrisk.source.SourceTree;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything one analysis reads, with the three trees it compares:
 * {@link Side#BASELINE} (repo1 where the migration started), {@link Side#HEAD} (repo1 now) and
 * {@link Side#TARGET} (repo2 now) — git commits or plain folders. Files are read on demand, parsed at most once.
 */
public final class AnalysisContext {

    public enum Side { BASELINE, HEAD, TARGET }

    private final SourceTree baseline;
    private final SourceTree head;
    private final SourceTree target;
    private final List<ChangedFile> changes;
    private final Map<String, List<String>> targetPathsByName;
    private final RefactoringIndex upstream;
    private final RefactoringIndex migration;
    private final Map<String, Optional<String>> texts = new HashMap<>();
    private final Map<String, Optional<JavaStructure>> structures = new HashMap<>();

    public AnalysisContext(SourceTree baseline, SourceTree head, SourceTree target, List<ChangedFile> changes,
                           Map<String, List<String>> targetPathsByName,
                           RefactoringIndex upstream, RefactoringIndex migration) {
        this.baseline = baseline;
        this.head = head;
        this.target = target;
        this.changes = changes;
        this.targetPathsByName = targetPathsByName;
        this.upstream = upstream;
        this.migration = migration;
    }

    public List<ChangedFile> changes() {
        return changes;
    }

    /** repo1@baseline -> repo1@HEAD: what the other team refactored. */
    public RefactoringIndex upstream() {
        return upstream;
    }

    /** repo1@baseline -> repo2@HEAD: where the migration put things. */
    public RefactoringIndex migration() {
        return migration;
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

    /** repo2 paths whose file name is {@code fileName}, for finding a class the migration moved. */
    public List<String> targetPathsNamed(String fileName) {
        return targetPathsByName.getOrDefault(fileName, List.of());
    }

    private SourceTree tree(Side side) {
        return switch (side) {
            case BASELINE -> baseline;
            case HEAD -> head;
            case TARGET -> target;
        };
    }
}
