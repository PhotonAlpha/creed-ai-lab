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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The Java half: every class / method / field set repo1 changed since the baseline, looked up in repo2.
 *
 * <p>For each element the question is the same — where is the baseline version of it in repo2, and is it
 * still the baseline version? In order:
 * <ol>
 *   <li>the migration run extracted / inlined / split the method -> {@code TARGET_SPLIT};</li>
 *   <li>it renamed or moved the method -> {@code RELOCATED};</li>
 *   <li>otherwise find the class (the migration's class moves, then the same path, then a file with the
 *       same name) and the method by signature in it: identical -> {@code AUTO_SYNC}, different ->
 *       {@code BOTH_MODIFIED}, absent -> {@code TARGET_MISSING}.</li>
 * </ol>
 */
@Component
public class JavaRiskAnalyzer {

    /** Where the baseline version of an element is in repo2, and in what state. */
    private record Outcome(RiskCategory category, TargetState state, Location target,
                           List<String> evidence, List<String> details) {
    }

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
        List<RiskItem> items = new ArrayList<>();
        items.addAll(upstreamRefactorings(ctx));

        List<ChangedFile> javaChanges = ctx.changes().stream().filter(ChangedFile::isJava).toList();
        Map<String, String> pairs = new LinkedHashMap<>();
        Set<String> added = new LinkedHashSet<>();
        Set<String> deleted = new LinkedHashSet<>();
        for (ChangedFile f : javaChanges) {
            switch (f.change()) {
                case MODIFIED, RENAMED -> pairs.put(f.oldPath(), f.newPath());
                case ADDED -> added.add(f.newPath());
                case DELETED -> deleted.add(f.oldPath());
            }
        }
        // git's rename detection gives up once a moved file also changed a lot; the upstream run still knows
        // the class moved. Pair those files so their methods are compared instead of reported as one
        // deleted and one added class.
        for (ClassMove move : ctx.upstream().classMoves().values()) {
            String from = move.refactoring().before().stream().map(CodeLocation::path)
                    .filter(deleted::contains).findFirst().orElse(null);
            if (from != null && added.contains(move.path())) {
                pairs.put(from, move.path());
                deleted.remove(from);
                added.remove(move.path());
            }
        }

        pairs.forEach((oldPath, newPath) -> items.addAll(comparePair(ctx, oldPath, newPath)));
        for (String path : deleted) {
            ctx.structure(Side.BASELINE, path).ifPresent(s -> s.topLevelTypes()
                    .forEach(t -> deletedClass(ctx, t, path).ifPresent(items::add)));
        }
        for (String path : added) {
            ctx.structure(Side.HEAD, path).ifPresent(s -> s.topLevelTypes()
                    .forEach(t -> items.add(addedClass(ctx, t, path))));
        }
        return items;
    }

    // ---------------------------------------------------------------- upstream refactorings

    /**
     * One item per structural refactoring repo1 itself made. The element is the baseline one; its repo2
     * state says whether the refactoring can be replayed there as is.
     */
    private List<RiskItem> upstreamRefactorings(AnalysisContext ctx) {
        List<RiskItem> items = new ArrayList<>();
        for (DetectedRefactoring r : ctx.upstream().refactorings()) {
            if (!r.structural() || r.before().isEmpty()) {
                continue;
            }
            CodeLocation before = r.before().getFirst();
            CodeLocation after = r.after().isEmpty() ? null : r.after().getFirst();
            Location upstream = after == null ? null : new Location(after.path(), after.startLine(), after.element());

            Optional<Map.Entry<TypeInfo, MethodInfo>> method = before.kind().equals("METHOD_DECLARATION")
                    ? methodAt(ctx, before) : Optional.empty();
            String element;
            ElementKind kind;
            Outcome outcome;
            if (method.isPresent()) {
                TypeInfo type = method.get().getKey();
                MethodInfo m = method.get().getValue();
                element = type.qualifiedName() + "#" + m.key();
                kind = ElementKind.METHOD;
                outcome = locateMethod(ctx, type, m, before.path());
            } else {
                element = before.element();
                kind = ElementKind.CLASS;
                ClassLookup c = locateClass(ctx, before.element(), before.path());
                outcome = c.found()
                        ? new Outcome(null, c.relocated() ? TargetState.RELOCATED : TargetState.UNCHANGED,
                        c.location(), c.evidence(), List.of())
                        : missingClass(c);
            }
            RiskCategory category = outcome.state() == TargetState.MISSING || outcome.state() == TargetState.SPLIT
                    ? outcome.category() : RiskCategory.UPSTREAM_REFACTORING;
            List<String> evidence = new ArrayList<>();
            evidence.add("upstream: " + r.description());
            evidence.addAll(outcome.evidence());
            items.add(RiskItem.of(category, kind, element, UpstreamChange.REFACTORED, upstream, outcome.target(),
                    outcome.state(), evidence, outcome.details()));
        }
        return items;
    }

    private static Optional<Map.Entry<TypeInfo, MethodInfo>> methodAt(AnalysisContext ctx, CodeLocation at) {
        return ctx.structure(Side.BASELINE, at.path()).flatMap(s -> s.types().values().stream()
                .flatMap(t -> t.methods().values().stream()
                        .filter(m -> at.overlaps(at.path(), m.startLine(), m.endLine()))
                        .map(m -> Map.entry(t, m)))
                .findFirst());
    }

    // ---------------------------------------------------------------- a modified file

    private List<RiskItem> comparePair(AnalysisContext ctx, String oldPath, String newPath) {
        Optional<JavaStructure> before = ctx.structure(Side.BASELINE, oldPath);
        Optional<JavaStructure> after = ctx.structure(Side.HEAD, newPath);
        if (before.isEmpty() || after.isEmpty()) {
            return List.of();
        }
        List<RiskItem> items = new ArrayList<>();
        Set<String> mappedHeadTypes = new HashSet<>();
        for (TypeInfo baseType : before.get().types().values()) {
            Optional<TypeInfo> headType = counterpart(ctx, baseType, before.get(), after.get());
            if (headType.isEmpty()) {
                deletedClass(ctx, baseType, oldPath).ifPresent(items::add);
                continue;
            }
            mappedHeadTypes.add(headType.get().qualifiedName());
            items.addAll(compareType(ctx, baseType, oldPath, headType.get(), newPath));
        }
        for (TypeInfo headType : after.get().types().values()) {
            if (!mappedHeadTypes.contains(headType.qualifiedName())) {
                items.add(addedClass(ctx, headType, newPath));
            }
        }
        return items;
    }

    /** The head version of a baseline type: same name, an upstream class move, or the file's only top-level type. */
    private static Optional<TypeInfo> counterpart(AnalysisContext ctx, TypeInfo baseType,
                                                  JavaStructure before, JavaStructure after) {
        Optional<TypeInfo> same = after.type(baseType.qualifiedName());
        if (same.isPresent()) {
            return same;
        }
        Optional<TypeInfo> moved = ctx.upstream().classMove(baseType.qualifiedName())
                .flatMap(m -> after.type(m.qualifiedName()));
        if (moved.isPresent()) {
            return moved;
        }
        if (baseType.topLevel() && before.topLevelTypes().size() == 1 && after.topLevelTypes().size() == 1) {
            return Optional.of(after.topLevelTypes().getFirst());
        }
        return Optional.empty();
    }

    private List<RiskItem> compareType(AnalysisContext ctx, TypeInfo baseType, String oldPath,
                                       TypeInfo headType, String newPath) {
        List<RiskItem> items = new ArrayList<>();
        for (MethodInfo base : baseType.methods().values()) {
            Optional<MethodInfo> head = headType.method(base.key());
            String element = headType.qualifiedName() + "#" + base.key();
            if (head.isPresent()) {
                if (!head.get().normalized().equals(base.normalized())) {
                    Outcome o = locateMethod(ctx, baseType, base, oldPath);
                    items.add(RiskItem.of(o.category(), ElementKind.METHOD, element, UpstreamChange.MODIFIED,
                            new Location(newPath, head.get().startLine(), element), o.target(), o.state(),
                            o.evidence(), o.details()));
                }
            } else if (!ctx.upstream().coversBefore(oldPath, base.startLine(), base.endLine())) {
                deletedMethod(ctx, baseType, base, oldPath).ifPresent(items::add);
            }
        }
        for (MethodInfo head : headType.methods().values()) {
            if (baseType.method(head.key()).isEmpty()
                    && !ctx.upstream().coversAfter(newPath, head.startLine(), head.endLine())) {
                items.add(addedMethod(ctx, baseType, oldPath, headType, head, newPath));
            }
        }
        if (!baseType.fields().equals(headType.fields())) {
            items.add(changedFields(ctx, baseType, oldPath, headType, newPath));
        }
        return items;
    }

    // ---------------------------------------------------------------- element kinds

    private Optional<RiskItem> deletedMethod(AnalysisContext ctx, TypeInfo baseType, MethodInfo base, String path) {
        Outcome o = locateMethod(ctx, baseType, base, path);
        if (o.state() == TargetState.MISSING || o.state() == TargetState.SPLIT) {
            return Optional.empty(); // already gone from repo2 as well: nothing to sync
        }
        String element = baseType.qualifiedName() + "#" + base.key();
        return Optional.of(RiskItem.of(RiskCategory.DELETED_UPSTREAM, ElementKind.METHOD, element,
                UpstreamChange.DELETED, new Location(path, base.startLine(), element), o.target(), o.state(),
                o.evidence(), o.details()));
    }

    private RiskItem addedMethod(AnalysisContext ctx, TypeInfo baseType, String oldPath,
                                 TypeInfo headType, MethodInfo head, String newPath) {
        String element = headType.qualifiedName() + "#" + head.key();
        Location upstream = new Location(newPath, head.startLine(), element);
        ClassLookup c = locateClass(ctx, baseType.qualifiedName(), oldPath);
        if (!c.found()) {
            Outcome o = missingClass(c);
            return RiskItem.of(o.category(), ElementKind.METHOD, element, UpstreamChange.ADDED, upstream, null,
                    o.state(), o.evidence(), List.of("the class it was added to is not in repo2"));
        }
        Optional<MethodInfo> existing = c.type().method(head.key());
        if (existing.isPresent()) {
            return RiskItem.of(RiskCategory.BOTH_MODIFIED, ElementKind.METHOD, element, UpstreamChange.ADDED,
                    upstream, new Location(c.path(), existing.get().startLine(), c.qualifiedName() + "#" + head.key()),
                    TargetState.MODIFIED, c.evidence(), List.of("repo2 already declares a method with this signature"));
        }
        return RiskItem.of(c.relocated() ? RiskCategory.RELOCATED : RiskCategory.NEW_CODE, ElementKind.METHOD,
                element, UpstreamChange.ADDED, upstream, c.location(),
                c.relocated() ? TargetState.RELOCATED : TargetState.ABSENT, c.evidence(), List.of());
    }

    private RiskItem changedFields(AnalysisContext ctx, TypeInfo baseType, String oldPath,
                                   TypeInfo headType, String newPath) {
        String element = headType.qualifiedName();
        List<String> details = new ArrayList<>();
        headType.fields().stream().filter(f -> !baseType.fields().contains(f)).forEach(f -> details.add("+ " + f));
        baseType.fields().stream().filter(f -> !headType.fields().contains(f)).forEach(f -> details.add("- " + f));
        Location upstream = new Location(newPath, headType.startLine(), element);
        ClassLookup c = locateClass(ctx, baseType.qualifiedName(), oldPath);
        if (!c.found()) {
            Outcome o = missingClass(c);
            return RiskItem.of(o.category(), ElementKind.FIELDS, element, UpstreamChange.MODIFIED, upstream, null,
                    o.state(), o.evidence(), details);
        }
        boolean unchanged = c.type().fields().equals(baseType.fields());
        RiskCategory category = !unchanged ? RiskCategory.BOTH_MODIFIED
                : c.relocated() ? RiskCategory.RELOCATED : RiskCategory.AUTO_SYNC;
        TargetState state = !unchanged ? TargetState.MODIFIED
                : c.relocated() ? TargetState.RELOCATED : TargetState.UNCHANGED;
        return RiskItem.of(category, ElementKind.FIELDS, element, UpstreamChange.MODIFIED, upstream, c.location(),
                state, c.evidence(), details);
    }

    private Optional<RiskItem> deletedClass(AnalysisContext ctx, TypeInfo baseType, String path) {
        ClassLookup c = locateClass(ctx, baseType.qualifiedName(), path);
        if (!c.found()) {
            return Optional.empty();
        }
        return Optional.of(RiskItem.of(RiskCategory.DELETED_UPSTREAM, ElementKind.CLASS, baseType.qualifiedName(),
                UpstreamChange.DELETED, new Location(path, baseType.startLine(), baseType.qualifiedName()),
                c.location(), c.relocated() ? TargetState.RELOCATED : TargetState.UNCHANGED, c.evidence(), List.of()));
    }

    private RiskItem addedClass(AnalysisContext ctx, TypeInfo headType, String path) {
        Location upstream = new Location(path, headType.startLine(), headType.qualifiedName());
        Optional<TypeInfo> existing = ctx.structure(Side.TARGET, path).flatMap(s -> s.type(headType.qualifiedName()));
        if (existing.isPresent()) {
            return RiskItem.of(RiskCategory.BOTH_MODIFIED, ElementKind.CLASS, headType.qualifiedName(),
                    UpstreamChange.ADDED, upstream, new Location(path, existing.get().startLine(), headType.qualifiedName()),
                    TargetState.MODIFIED, List.of(), List.of("repo2 already has a class with this name at this path"));
        }
        return RiskItem.of(RiskCategory.NEW_CODE, ElementKind.CLASS, headType.qualifiedName(), UpstreamChange.ADDED,
                upstream, null, TargetState.ABSENT, List.of(), List.of());
    }

    // ---------------------------------------------------------------- repo2 lookups

    /** Where the baseline method {@code base} of {@code baseType} (in {@code path}) is in repo2. */
    private Outcome locateMethod(AnalysisContext ctx, TypeInfo baseType, MethodInfo base, String path) {
        Optional<Move> split = ctx.migration().methodSplit(path, base.startLine(), base.endLine());
        if (split.isPresent()) {
            return new Outcome(RiskCategory.TARGET_SPLIT, TargetState.SPLIT, location(split.get().after()),
                    List.of("migration: " + split.get().refactoring().description()), List.of());
        }
        Optional<Move> moved = ctx.migration().methodRelocation(path, base.startLine(), base.endLine());
        if (moved.isPresent()) {
            return new Outcome(RiskCategory.RELOCATED, TargetState.RELOCATED, location(moved.get().after()),
                    List.of("migration: " + moved.get().refactoring().description()), List.of());
        }
        ClassLookup c = locateClass(ctx, baseType.qualifiedName(), path);
        if (!c.found()) {
            return missingClass(c);
        }
        Optional<MethodInfo> target = c.type().method(base.key());
        if (target.isPresent()) {
            Location at = new Location(c.path(), target.get().startLine(), c.qualifiedName() + "#" + base.key());
            if (target.get().normalized().equals(base.normalized())) {
                return c.relocated()
                        ? new Outcome(RiskCategory.RELOCATED, TargetState.RELOCATED, at, c.evidence(), List.of())
                        : new Outcome(RiskCategory.AUTO_SYNC, TargetState.UNCHANGED, at, c.evidence(), List.of());
            }
            return new Outcome(RiskCategory.BOTH_MODIFIED, TargetState.MODIFIED, at, c.evidence(),
                    List.of("repo2 changed this method since the baseline"));
        }
        if (c.type().hasMethodNamed(base.name())) {
            List<String> overloads = c.type().methods().values().stream()
                    .filter(m -> m.name().equals(base.name())).map(MethodInfo::key).toList();
            return new Outcome(RiskCategory.BOTH_MODIFIED, TargetState.MODIFIED, c.location(), c.evidence(),
                    List.of("signature differs in repo2: " + String.join(", ", overloads)));
        }
        return new Outcome(RiskCategory.TARGET_MISSING, TargetState.MISSING, c.location(), c.evidence(),
                List.of("the class is in repo2, the method is not"));
    }

    private static Outcome missingClass(ClassLookup c) {
        return c.split()
                ? new Outcome(RiskCategory.TARGET_SPLIT, TargetState.SPLIT, null, c.evidence(), List.of())
                : new Outcome(RiskCategory.TARGET_MISSING, TargetState.MISSING, null, c.evidence(),
                List.of("the class is not in repo2"));
    }

    /**
     * The migration run's class moves first (they are what RefactoringMiner proved), then the same path,
     * then any repo2 file with the same file name — which catches a package move RefactoringMiner did not
     * report because only part of the class came along.
     */
    private ClassLookup locateClass(AnalysisContext ctx, String qualifiedName, String baselinePath) {
        Optional<ClassMove> move = ctx.migration().classMove(qualifiedName);
        if (move.isPresent()) {
            ClassMove m = move.get();
            Optional<TypeInfo> type = ctx.structure(Side.TARGET, m.path()).flatMap(s -> s.type(m.qualifiedName()));
            if (type.isPresent()) {
                return new ClassLookup(type.get(), m.qualifiedName(), m.path(), true, false,
                        List.of("migration: " + m.refactoring().description()));
            }
        }
        Optional<TypeInfo> samePath = ctx.structure(Side.TARGET, baselinePath).flatMap(s -> s.type(qualifiedName));
        if (samePath.isPresent()) {
            return new ClassLookup(samePath.get(), qualifiedName, baselinePath, false, false, List.of());
        }
        String fileName = baselinePath.substring(baselinePath.lastIndexOf('/') + 1);
        String simpleName = RefactoringIndex.simpleName(qualifiedName);
        for (String candidate : ctx.targetPathsNamed(fileName)) {
            Optional<JavaStructure> s = ctx.structure(Side.TARGET, candidate);
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
        Optional<DetectedRefactoring> split = ctx.migration().classSplit(qualifiedName);
        return new ClassLookup(null, qualifiedName, null, false, split.isPresent(),
                split.map(r -> List.of("migration: " + r.description())).orElse(List.of()));
    }

    private static Location location(CodeLocation at) {
        return at == null ? null : new Location(at.path(), at.startLine(), at.element());
    }
}
