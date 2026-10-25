package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.domain.CodeLocation;
import com.creed.resource.migrationrisk.domain.DetectedRefactoring;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One RefactoringMiner run, indexed by what was there <em>before</em>: "the method at lines 6-12 of
 * {@code OrderProcessor.java}" -> where it went. Built once for the upstream run and once for the migration
 * run; both share the baseline tree, so a "before" location means the same code in both.
 *
 * <p>Methods are matched by file and overlapping line range rather than by signature: RefactoringMiner
 * prints signatures as {@code public handle(orderId String) : String}, a format nothing else here uses,
 * while its line ranges and JDT's are measured on the same source.
 */
public final class RefactoringIndex {

    /** The method now lives elsewhere, intact: renamed, moved to another class, pulled up / pushed down. */
    static final Set<String> METHOD_RELOCATIONS = Set.of(
            "RENAME_METHOD", "MOVE_OPERATION", "MOVE_AND_RENAME_OPERATION", "PULL_UP_OPERATION", "PUSH_DOWN_OPERATION");
    /** The method's code was redistributed: there is no single place to apply an upstream edit to. */
    static final Set<String> METHOD_SPLITS = Set.of(
            "EXTRACT_OPERATION", "EXTRACT_AND_MOVE_OPERATION", "INLINE_OPERATION", "MOVE_AND_INLINE_OPERATION",
            "SPLIT_OPERATION", "MERGE_OPERATION");
    static final Set<String> CLASS_RELOCATIONS = Set.of(
            "MOVE_CLASS", "RENAME_CLASS", "MOVE_RENAME_CLASS", "MOVE_SOURCE_FOLDER",
            "RENAME_PACKAGE", "MOVE_PACKAGE");
    static final Set<String> CLASS_SPLITS = Set.of(
            "EXTRACT_CLASS", "EXTRACT_SUPERCLASS", "EXTRACT_INTERFACE", "EXTRACT_SUBCLASS",
            "SPLIT_CLASS", "MERGE_CLASS", "COLLAPSE_HIERARCHY", "SPLIT_PACKAGE", "MERGE_PACKAGE");

    private static final Set<String> TYPE_KINDS = Set.of(
            "TYPE_DECLARATION", "ENUM_DECLARATION", "RECORD_DECLARATION", "ANNOTATION_TYPE_DECLARATION");

    public static boolean isStructural(String type) {
        return METHOD_RELOCATIONS.contains(type) || METHOD_SPLITS.contains(type)
                || CLASS_RELOCATIONS.contains(type) || CLASS_SPLITS.contains(type);
    }

    /** {@code before} -> {@code after}, and the refactoring that says so. */
    public record Move(CodeLocation before, CodeLocation after, DetectedRefactoring refactoring) {
    }

    /** A class's new name and file. */
    public record ClassMove(String qualifiedName, String path, DetectedRefactoring refactoring) {
    }

    private final List<DetectedRefactoring> refactorings;
    private final List<Move> methodRelocations = new ArrayList<>();
    private final List<Move> methodSplits = new ArrayList<>();
    private final Map<String, ClassMove> classMoves = new HashMap<>();
    private final Map<String, DetectedRefactoring> classSplits = new HashMap<>();

    public RefactoringIndex(List<DetectedRefactoring> refactorings) {
        this.refactorings = List.copyOf(refactorings);
        for (DetectedRefactoring r : refactorings) {
            if (METHOD_RELOCATIONS.contains(r.type())) {
                firstMethod(r.before()).ifPresent(before -> methodRelocations.add(
                        new Move(before, firstMethod(r.after()).orElse(null), r)));
            } else if (METHOD_SPLITS.contains(r.type())) {
                // Every method on the left lost code to (or received code from) the right.
                r.before().stream().filter(c -> c.kind().equals("METHOD_DECLARATION"))
                        .forEach(before -> methodSplits.add(new Move(before, firstMethod(r.after()).orElse(null), r)));
            } else if (CLASS_RELOCATIONS.contains(r.type())) {
                pairTypes(r);
            } else if (CLASS_SPLITS.contains(r.type())) {
                r.before().stream().filter(c -> TYPE_KINDS.contains(c.kind()))
                        .forEach(c -> classSplits.putIfAbsent(c.element(), r));
            }
        }
    }

    /**
     * Move/rename class carry one type on each side; package-level refactorings carry many. Pair by index
     * when the counts match, otherwise by simple name (a package rename keeps the class names).
     */
    private void pairTypes(DetectedRefactoring r) {
        List<CodeLocation> before = r.before().stream().filter(c -> TYPE_KINDS.contains(c.kind())).toList();
        List<CodeLocation> after = r.after().stream().filter(c -> TYPE_KINDS.contains(c.kind())).toList();
        for (int i = 0; i < before.size(); i++) {
            CodeLocation from = before.get(i);
            CodeLocation to = before.size() == after.size() ? after.get(i)
                    : after.stream().filter(a -> simpleName(a.element()).equals(simpleName(from.element())))
                    .findFirst().orElse(null);
            if (to != null) {
                classMoves.putIfAbsent(from.element(), new ClassMove(to.element(), to.path(), r));
            }
        }
    }

    private static Optional<CodeLocation> firstMethod(List<CodeLocation> side) {
        return side.stream().filter(c -> c.kind().equals("METHOD_DECLARATION")).findFirst();
    }

    static String simpleName(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    public List<DetectedRefactoring> refactorings() {
        return refactorings;
    }

    public Optional<Move> methodRelocation(String path, int startLine, int endLine) {
        return methodRelocations.stream().filter(m -> m.before().overlaps(path, startLine, endLine)).findFirst();
    }

    public Optional<Move> methodSplit(String path, int startLine, int endLine) {
        return methodSplits.stream().filter(m -> m.before().overlaps(path, startLine, endLine)).findFirst();
    }

    /** Whether any structural refactoring's {@code after} side covers this range — i.e. it is not "just added". */
    public boolean coversAfter(String path, int startLine, int endLine) {
        return refactorings.stream().filter(r -> isStructural(r.type()))
                .flatMap(r -> r.after().stream())
                .anyMatch(c -> c.kind().equals("METHOD_DECLARATION") && c.overlaps(path, startLine, endLine));
    }

    /** Same for the {@code before} side — the method did not just disappear. */
    public boolean coversBefore(String path, int startLine, int endLine) {
        return refactorings.stream().filter(r -> isStructural(r.type()))
                .flatMap(r -> r.before().stream())
                .anyMatch(c -> c.kind().equals("METHOD_DECLARATION") && c.overlaps(path, startLine, endLine));
    }

    public Optional<ClassMove> classMove(String qualifiedName) {
        return Optional.ofNullable(classMoves.get(qualifiedName));
    }

    public Map<String, ClassMove> classMoves() {
        return classMoves;
    }

    public Optional<DetectedRefactoring> classSplit(String qualifiedName) {
        return Optional.ofNullable(classSplits.get(qualifiedName));
    }
}
