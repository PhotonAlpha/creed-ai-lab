package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.domain.CodeLocation;
import com.creed.resource.migrationrisk.domain.DetectedRefactoring;
import com.creed.resource.migrationrisk.domain.ElementKind;
import com.creed.resource.migrationrisk.domain.Location;
import com.creed.resource.migrationrisk.domain.RiskCategory;
import com.creed.resource.migrationrisk.domain.RiskItem;
import com.creed.resource.migrationrisk.domain.TargetState;
import com.creed.resource.migrationrisk.domain.UpstreamChange;
import com.creed.resource.migrationrisk.git.ChangedFile;
import com.creed.resource.migrationrisk.service.AnalysisContext.Side;
import com.creed.resource.migrationrisk.service.JavaStructure.MethodInfo;
import com.creed.resource.migrationrisk.service.JavaStructure.TypeInfo;
import com.creed.resource.migrationrisk.service.RefactoringIndex.ClassMove;
import com.creed.resource.migrationrisk.service.RefactoringIndex.Move;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The Java half: every class of a Java file that differs, looked up in repo2 and compared member by member.
 *
 * <p>For a repo1 class: where is it in repo2 — RefactoringMiner's class move, the same path, a file with the same
 * name — and then per method: identical (normalized: formatting, Javadoc and imports do not count) -> nothing;
 * different -> {@code CONTENT_DIFFERS}; not there -> RefactoringMiner's method move ({@code RELOCATED}, or
 * {@code ALREADY_SYNCED} when the moved method is identical), its extract / inline ({@code TARGET_SPLIT}), else
 * {@code TARGET_MISSING}. What only repo2 has is {@code ONLY_IN_REPO2}. A class found nowhere is {@code TARGET_MISSING}.
 */
@Component
public class JavaRiskAnalyzer {

    /** A class found (or not) in repo2. */
    private record ClassLookup(TypeInfo type, String qualifiedName, String path, boolean relocated,
                               boolean split, List<String> evidence) {

        boolean found() {
            return type != null;
        }

        Location location() {
            return new Location(path, type.startLine(), qualifiedName);
        }
    }

    public List<RiskItem> analyze(AnalysisContext ctx) {
        List<ChangedFile> java = ctx.changes().stream().filter(ChangedFile::isJava).toList();
        List<RiskItem> items = new ArrayList<>();
        // repo2 files some repo1 class was found in: a file only repo2 has is not "extra" when it is where a class went
        Set<String> matched = new HashSet<>();
        int done = 0;
        for (ChangedFile f : java) {
            if (f.newPath() == null) {
                continue;
            }
            ctx.progress().step(Progress.Stage.JAVA, done++, java.size(), f.newPath());
            items.addAll(compareFile(ctx, f.newPath(), f.oldPath(), matched));
        }
        for (ChangedFile f : java) {
            if (f.newPath() != null || matched.contains(f.oldPath())) {
                continue;
            }
            ctx.progress().step(Progress.Stage.JAVA, done++, java.size(), f.oldPath());
            ctx.structure(Side.REPO2, f.oldPath()).ifPresent(s -> s.topLevelTypes().forEach(t -> items.add(
                    RiskItem.of(RiskCategory.ONLY_IN_REPO2, ElementKind.CLASS, t.qualifiedName(), UpstreamChange.DELETED,
                            null, new Location(f.oldPath(), t.startLine(), t.qualifiedName()), TargetState.EXTRA,
                            List.of(), List.of("only repo2 has this class")))));
        }
        return items;
    }

    /** {@code repo2Path}: repo2's file at the same path (or an exact rename); null when repo2 has none there. */
    private List<RiskItem> compareFile(AnalysisContext ctx, String path, String repo2Path, Set<String> matched) {
        Optional<JavaStructure> mine = ctx.structure(Side.REPO1, path);
        if (mine.isEmpty()) {
            return List.of();
        }
        Optional<JavaStructure> theirs = ctx.structure(Side.REPO2, repo2Path);
        List<RiskItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TypeInfo type : mine.get().types().values()) {
            Optional<TypeInfo> same = theirs.flatMap(t -> counterpart(ctx, type, mine.get(), t));
            ClassLookup c = same.map(t -> new ClassLookup(t, t.qualifiedName(), repo2Path, false, false, List.<String>of()))
                    .orElseGet(() -> locateClass(ctx, type.qualifiedName(), path));
            if (!c.found()) {
                // a nested class goes with its outer one: report only what is missing at the top
                if (type.topLevel() || !missingOuter(ctx, type, mine.get(), path)) {
                    items.add(missingClass(type, path, c));
                }
                continue;
            }
            matched.add(c.path());
            seen.add(c.path() + "#" + c.qualifiedName());
            items.addAll(compareType(ctx, type, path, c));
        }
        // classes in repo2's same-path file that no repo1 class matched
        theirs.ifPresent(t -> t.topLevelTypes().stream()
                .filter(other -> !seen.contains(repo2Path + "#" + other.qualifiedName()))
                .forEach(other -> items.add(RiskItem.of(RiskCategory.ONLY_IN_REPO2, ElementKind.CLASS,
                        other.qualifiedName(), UpstreamChange.DELETED, new Location(path, null, null),
                        new Location(repo2Path, other.startLine(), other.qualifiedName()), TargetState.EXTRA, List.of(),
                        List.of("only repo2 has this class in this file")))));
        return items;
    }

    private boolean missingOuter(AnalysisContext ctx, TypeInfo nested, JavaStructure mine, String path) {
        String outer = nested.qualifiedName().substring(0, nested.qualifiedName().lastIndexOf('.'));
        return mine.type(outer).isPresent() && !locateClass(ctx, outer, path).found();
    }

    /** repo2's version of a repo1 type in the same-path file: same name, a class move, or the file's only top-level type. */
    private static Optional<TypeInfo> counterpart(AnalysisContext ctx, TypeInfo type, JavaStructure mine, JavaStructure theirs) {
        Optional<TypeInfo> same = theirs.type(type.qualifiedName());
        if (same.isPresent()) {
            return same;
        }
        Optional<TypeInfo> moved = ctx.refactorings().classMove(type.qualifiedName())
                .flatMap(m -> theirs.type(m.qualifiedName()));
        if (moved.isPresent()) {
            return moved;
        }
        if (type.topLevel() && mine.topLevelTypes().size() == 1 && theirs.topLevelTypes().size() == 1) {
            return Optional.of(theirs.topLevelTypes().getFirst());
        }
        return Optional.empty();
    }

    private static RiskItem missingClass(TypeInfo type, String path, ClassLookup c) {
        Location at = new Location(path, type.startLine(), type.qualifiedName());
        return c.split()
                ? RiskItem.of(RiskCategory.TARGET_SPLIT, ElementKind.CLASS, type.qualifiedName(), UpstreamChange.ADDED,
                at, null, TargetState.SPLIT, c.evidence(), List.of())
                : RiskItem.of(RiskCategory.TARGET_MISSING, ElementKind.CLASS, type.qualifiedName(), UpstreamChange.ADDED,
                at, null, TargetState.MISSING, c.evidence(), List.of("the class is not in repo2"));
    }

    // ---------------------------------------------------------------- one class, member by member

    private List<RiskItem> compareType(AnalysisContext ctx, TypeInfo type, String path, ClassLookup c) {
        List<RiskItem> items = new ArrayList<>();
        TypeInfo there = c.type();
        String text = ctx.text(Side.REPO1, path).orElse("");
        String thereText = ctx.text(Side.REPO2, c.path()).orElse("");
        boolean differs = false;
        for (MethodInfo m : type.methods().values()) {
            String element = type.qualifiedName() + "#" + m.key();
            Location at = new Location(path, m.startLine(), element);
            Optional<MethodInfo> same = there.method(m.key());
            if (same.isPresent()) {
                if (!same.get().normalized().equals(m.normalized())) {
                    differs = true;
                    items.add(RiskItem.of(RiskCategory.CONTENT_DIFFERS, ElementKind.METHOD, element,
                            UpstreamChange.MODIFIED, at, new Location(c.path(), same.get().startLine(),
                                    c.qualifiedName() + "#" + m.key()), TargetState.MODIFIED, c.evidence(),
                            FileRiskAnalyzer.lineDiff(lines(text, m), m.startLine(),
                                    lines(thereText, same.get()), same.get().startLine())));
                }
                continue;
            }
            differs = true;
            items.add(elsewhere(ctx, type, m, path, c, element, at));
        }
        for (MethodInfo m : there.methods().values()) {
            if (type.method(m.key()).isEmpty() && !type.hasMethodNamed(m.name())
                    && !ctx.refactorings().coversAfter(c.path(), m.startLine(), m.endLine())) {
                differs = true;
                String element = type.qualifiedName() + "#" + m.key();
                items.add(RiskItem.of(RiskCategory.ONLY_IN_REPO2, ElementKind.METHOD, element, UpstreamChange.DELETED,
                        new Location(path, type.startLine(), element),
                        new Location(c.path(), m.startLine(), c.qualifiedName() + "#" + m.key()), TargetState.EXTRA,
                        c.evidence(), List.of("only repo2 has this method")));
            }
        }
        if (!type.fields().equals(there.fields())) {
            differs = true;
            List<String> details = new ArrayList<>();
            type.fields().stream().filter(f -> !there.fields().contains(f)).forEach(f -> details.add("- " + f));
            there.fields().stream().filter(f -> !type.fields().contains(f)).forEach(f -> details.add("+ " + f));
            items.add(RiskItem.of(c.relocated() ? RiskCategory.RELOCATED : RiskCategory.CONTENT_DIFFERS,
                    ElementKind.FIELDS, type.qualifiedName(), UpstreamChange.MODIFIED,
                    new Location(path, type.startLine(), type.qualifiedName()), c.location(),
                    c.relocated() ? TargetState.RELOCATED : TargetState.MODIFIED, c.evidence(), details));
        }
        if (c.relocated()) {
            // one line for the class itself: where it went, and whether anything in it still differs
            items.add(RiskItem.of(differs ? RiskCategory.RELOCATED : RiskCategory.ALREADY_SYNCED, ElementKind.CLASS,
                    type.qualifiedName(), UpstreamChange.MODIFIED,
                    new Location(path, type.startLine(), type.qualifiedName()), c.location(), TargetState.RELOCATED,
                    c.evidence(), List.of(differs ? "repo2 has this class at " + c.path() + ", with differences"
                            : "repo2 has this class at " + c.path() + ", identical")));
        }
        return items;
    }

    /** A repo1 method its class's repo2 version does not declare by that signature. */
    private RiskItem elsewhere(AnalysisContext ctx, TypeInfo type, MethodInfo m, String path, ClassLookup c,
                               String element, Location at) {
        Optional<Move> split = ctx.refactorings().methodSplit(path, m.startLine(), m.endLine());
        if (split.isPresent()) {
            return RiskItem.of(RiskCategory.TARGET_SPLIT, ElementKind.METHOD, element, UpstreamChange.MODIFIED, at,
                    location(split.get().after()), TargetState.SPLIT,
                    List.of("repo2: " + split.get().refactoring().description()), List.of());
        }
        Optional<Move> moved = ctx.refactorings().methodRelocation(path, m.startLine(), m.endLine());
        if (moved.isPresent()) {
            CodeLocation after = moved.get().after();
            Optional<MethodInfo> there = after == null ? Optional.empty() : methodAt(ctx, after);
            boolean same = there.isPresent() && there.get().normalized()
                    .replace(there.get().name() + "(", "(").equals(m.normalized().replace(m.name() + "(", "("));
            return RiskItem.of(same ? RiskCategory.ALREADY_SYNCED : RiskCategory.RELOCATED, ElementKind.METHOD, element,
                    UpstreamChange.MODIFIED, at, location(after), TargetState.RELOCATED,
                    List.of("repo2: " + moved.get().refactoring().description()),
                    same ? List.of() : there.map(t -> FileRiskAnalyzer.lineDiff(
                            lines(ctx.text(Side.REPO1, path).orElse(""), m), m.startLine(),
                            lines(ctx.text(Side.REPO2, after.path()).orElse(""), t), t.startLine())).orElse(List.of()));
        }
        if (c.type().hasMethodNamed(m.name())) {
            List<String> overloads = c.type().methods().values().stream()
                    .filter(o -> o.name().equals(m.name())).map(MethodInfo::key).toList();
            return RiskItem.of(RiskCategory.CONTENT_DIFFERS, ElementKind.METHOD, element, UpstreamChange.MODIFIED, at,
                    c.location(), TargetState.MODIFIED, c.evidence(),
                    List.of("signature differs in repo2: " + String.join(", ", overloads)));
        }
        return RiskItem.of(RiskCategory.TARGET_MISSING, ElementKind.METHOD, element, UpstreamChange.ADDED, at,
                c.location(), TargetState.MISSING, c.evidence(), List.of("the class is in repo2, the method is not"));
    }

    // ---------------------------------------------------------------- repo2 lookups

    /**
     * RefactoringMiner's class moves first (what it proved), then the same path, then any repo2 file with the same
     * file name — which catches a package move RefactoringMiner did not report because only part of the class
     * came along.
     */
    private ClassLookup locateClass(AnalysisContext ctx, String qualifiedName, String path) {
        Optional<ClassMove> move = ctx.refactorings().classMove(qualifiedName);
        if (move.isPresent()) {
            ClassMove m = move.get();
            Optional<TypeInfo> type = ctx.structure(Side.REPO2, m.path()).flatMap(s -> s.type(m.qualifiedName()));
            if (type.isPresent()) {
                return new ClassLookup(type.get(), m.qualifiedName(), m.path(), true, false,
                        List.of("repo2: " + m.refactoring().description()));
            }
        }
        Optional<TypeInfo> samePath = ctx.structure(Side.REPO2, path).flatMap(s -> s.type(qualifiedName));
        if (samePath.isPresent()) {
            return new ClassLookup(samePath.get(), qualifiedName, path, false, false, List.of());
        }
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        String simpleName = RefactoringIndex.simpleName(qualifiedName);
        for (String candidate : ctx.repo2PathsNamed(fileName)) {
            Optional<JavaStructure> s = ctx.structure(Side.REPO2, candidate);
            Optional<TypeInfo> exact = s.flatMap(st -> st.type(qualifiedName));
            if (exact.isPresent()) {
                return new ClassLookup(exact.get(), qualifiedName, candidate, true, false,
                        List.of("same class found at " + candidate));
            }
            Optional<TypeInfo> sameName = s.flatMap(st -> st.types().values().stream()
                    .filter(t -> t.simpleName().equals(simpleName)).findFirst());
            if (sameName.isPresent()) {
                return new ClassLookup(sameName.get(), sameName.get().qualifiedName(), candidate, true, false,
                        List.of("class with the same name found at " + candidate
                                + " (not reported as a move: check it is the same class)"));
            }
        }
        Optional<DetectedRefactoring> split = ctx.refactorings().classSplit(qualifiedName);
        return new ClassLookup(null, qualifiedName, null, false, split.isPresent(),
                split.map(r -> List.of("repo2: " + r.description())).orElse(List.of()));
    }

    /** The repo2 method RefactoringMiner points at (its own names cannot be looked up: match by line range). */
    private static Optional<MethodInfo> methodAt(AnalysisContext ctx, CodeLocation at) {
        return ctx.structure(Side.REPO2, at.path()).flatMap(s -> s.types().values().stream()
                .flatMap(t -> t.methods().values().stream())
                .filter(m -> at.overlaps(at.path(), m.startLine(), m.endLine()))
                .findFirst());
    }

    /** The method's own lines of its file, for a line diff. */
    private static String lines(String text, MethodInfo m) {
        List<String> all = text.lines().toList();
        int from = Math.max(0, m.startLine() - 1);
        int to = Math.min(all.size(), m.endLine());
        return from >= to ? "" : String.join("\n", all.subList(from, to)) + "\n";
    }

    private static Location location(CodeLocation at) {
        return at == null ? null : new Location(at.path(), at.startLine(), at.element());
    }
}
