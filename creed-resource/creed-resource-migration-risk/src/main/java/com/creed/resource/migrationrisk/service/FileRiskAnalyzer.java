package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.config.MigrationRiskProperties;
import com.creed.resource.migrationrisk.config.MigrationRiskProperties.ApiRule;
import com.creed.resource.migrationrisk.domain.ElementKind;
import com.creed.resource.migrationrisk.domain.Location;
import com.creed.resource.migrationrisk.domain.RiskCategory;
import com.creed.resource.migrationrisk.domain.RiskItem;
import com.creed.resource.migrationrisk.domain.RiskLevel;
import com.creed.resource.migrationrisk.domain.TargetState;
import com.creed.resource.migrationrisk.domain.UpstreamChange;
import com.creed.resource.migrationrisk.git.ChangedFile;
import com.creed.resource.migrationrisk.service.AnalysisContext.Side;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Everything RefactoringMiner does not look at: pom.xml, templates repo2 converted to another format, every other
 * non-Java file, and migration-sensitive API usage in the lines repo1 added (any file type). Properties / yml are
 * left to {@link GlobalConfigAnalyzer}, which compares keys across all files instead of file by file.
 */
@Component
public class FileRiskAnalyzer {

    /** Per rule and file; more matches are summarised, not listed. */
    private static final int MAX_MATCH_LINES = 20;

    private record CompiledRule(ApiRule rule, Pattern pattern) {
    }

    private final List<CompiledRule> rules;
    private final GlobalConfigAnalyzer configs;
    /** Lower-case extension -> the one repo2 converted it to ({@code vm -> ftl}). */
    private final Map<String, String> conversions;

    public FileRiskAnalyzer(MigrationRiskProperties properties, GlobalConfigAnalyzer configs) {
        this.rules = properties.apiRules().stream().map(r -> new CompiledRule(r, r.compiled())).toList();
        this.configs = configs;
        this.conversions = properties.extensionMappings().entrySet().stream().collect(Collectors.toMap(
                e -> e.getKey().toLowerCase(Locale.ROOT), e -> e.getValue().toLowerCase(Locale.ROOT)));
    }

    public List<RiskItem> analyze(AnalysisContext ctx) {
        List<RiskItem> items = new ArrayList<>();
        Map<String, Boolean> targetHasExtension = new HashMap<>();
        int done = 0;
        for (ChangedFile file : ctx.changes()) {
            ctx.progress().step(Progress.Stage.FILES, done++,
                    ctx.changes().size(), file.path());
            items.addAll(apiUsage(ctx, file));
            if (file.isJava() || configs.handles(file.path())) {
                continue;
            }
            if (ConfigDiff.isPom(file.path())) {
                items.add(config(ctx, file, RiskCategory.DEPENDENCY_CHANGED, ElementKind.DEPENDENCY));
            } else {
                Optional<RiskItem> converted = converted(ctx, file, targetHasExtension);
                (converted.isPresent() ? converted : otherFile(ctx, file)).ifPresent(items::add);
            }
        }
        return items;
    }

    /**
     * A file whose format repo2 changed — {@code templates/invoice.vm} is {@code templates/invoice.ftl} there.
     * Byte comparison would call it MISSING; it is found, but the change has to be re-written in the other
     * syntax, so the item carries the upstream line diff to port. Empty when no mapping applies or repo2 still
     * has the file under its old name (then it is an ordinary file).
     */
    private Optional<RiskItem> converted(AnalysisContext ctx, ChangedFile file, Map<String, Boolean> targetHasExtension) {
        String path = file.path();
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot < path.lastIndexOf('/')) {
            return Optional.empty();
        }
        String from = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        String to = conversions.get(from);
        if (to == null || ctx.contentId(Side.TARGET, path).isPresent()) {
            return Optional.empty();
        }
        String counterpart = path.substring(0, dot + 1) + to;
        Optional<String> found = ctx.contentId(Side.TARGET, counterpart).isPresent() ? Optional.of(counterpart)
                : closest(ctx.targetPathsNamed(counterpart.substring(counterpart.lastIndexOf('/') + 1)), counterpart);
        Location upstream = new Location(path, null, null);
        List<String> evidence = List.of("." + from + " -> ." + to + " (migration-risk.extension-mappings)");
        if (found.isPresent()) {
            Location target = new Location(found.get(), null, null);
            if (file.change() == ChangedFile.Change.DELETED) {
                return Optional.of(RiskItem.of(RiskCategory.DELETED_UPSTREAM, ElementKind.FILE, path,
                        UpstreamChange.DELETED, upstream, target, TargetState.RELOCATED, evidence,
                        List.of("repo2 has it as " + found.get() + ": delete it there too?")));
            }
            List<String> details = new ArrayList<>();
            details.add("repo2 has it as " + found.get() + ": re-write this change in ." + to + " syntax");
            details.addAll(lineDiff(ctx.text(Side.BASELINE, file.oldPath()).orElse(""),
                    ctx.text(Side.HEAD, file.newPath()).orElse("")));
            return Optional.of(RiskItem.of(RiskCategory.TEMPLATE_CONVERTED, ElementKind.FILE, path, change(file),
                    upstream, target, TargetState.RELOCATED, evidence, details));
        }
        // A new upstream template in a format repo2 dropped entirely: it has to be written in the new one.
        boolean converts = targetHasExtension.computeIfAbsent(to, ext -> hasExtension(ctx, ext))
                && !targetHasExtension.computeIfAbsent(from, ext -> hasExtension(ctx, ext));
        if (file.change() == ChangedFile.Change.ADDED && converts) {
            List<String> details = new ArrayList<>();
            details.add("new ." + from + " file; repo2 has no ." + from + " files but uses ." + to + ": write it as ."
                    + to);
            details.addAll(lineDiff("", ctx.text(Side.HEAD, file.newPath()).orElse("")));
            return Optional.of(RiskItem.of(RiskCategory.TEMPLATE_CONVERTED, ElementKind.FILE, path, change(file),
                    upstream, null, TargetState.ABSENT, evidence, details));
        }
        return Optional.empty();
    }

    private static boolean hasExtension(AnalysisContext ctx, String extension) {
        String suffix = "." + extension;
        return ctx.paths(Side.TARGET).stream().anyMatch(p -> p.toLowerCase(Locale.ROOT).endsWith(suffix));
    }

    /** The same-named candidate sharing the longest directory tail with {@code path}. */
    private static Optional<String> closest(List<String> candidates, String path) {
        return candidates.stream().max(Comparator.comparingInt((String c) -> commonTail(c, path))
                .thenComparing(Comparator.reverseOrder()));
    }

    private static int commonTail(String a, String b) {
        String[] x = a.split("/");
        String[] y = b.split("/");
        int n = 0;
        while (n < x.length && n < y.length && x[x.length - 1 - n].equals(y[y.length - 1 - n])) {
            n++;
        }
        return n;
    }

    /** {@code - L4: old} / {@code + L4: new} per changed line (histogram diff), capped like API matches. */
    static List<String> lineDiff(String before, String after) {
        RawText a = new RawText(before.getBytes(StandardCharsets.UTF_8));
        RawText b = new RawText(after.getBytes(StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>();
        int count = 0;
        for (Edit edit : new HistogramDiff().diff(RawTextComparator.DEFAULT, a, b)) {
            for (int i = edit.getBeginA(); i < edit.getEndA(); i++) {
                if (count++ < MAX_MATCH_LINES) {
                    lines.add("- L" + (i + 1) + ": " + a.getString(i).strip());
                }
            }
            for (int i = edit.getBeginB(); i < edit.getEndB(); i++) {
                if (count++ < MAX_MATCH_LINES) {
                    lines.add("+ L" + (i + 1) + ": " + b.getString(i).strip());
                }
            }
        }
        if (count > MAX_MATCH_LINES) {
            lines.add("... " + (count - MAX_MATCH_LINES) + " more lines");
        }
        return lines;
    }

    private RiskItem config(AnalysisContext ctx, ChangedFile file, RiskCategory category, ElementKind kind) {
        String before = ctx.text(Side.BASELINE, file.oldPath()).orElse(null);
        String after = ctx.text(Side.HEAD, file.newPath()).orElse(null);
        String target = ctx.text(Side.TARGET, file.path()).orElse(null);
        List<String> details;
        try {
            details = ConfigDiff.pomLines(before, after, target);
        } catch (RuntimeException e) {
            // Unparseable on one side (a templated yml, a broken pom): still HIGH, just without key detail.
            details = List.of("could not compare keys: " + e.getMessage());
        }
        if (details.isEmpty()) {
            details = List.of("formatting or comments only");
        }
        return RiskItem.of(category, kind, file.path(), change(file), new Location(file.path(), null, null),
                target == null ? null : new Location(file.path(), null, null), null, List.of(), details);
    }

    /** Byte comparison with repo2 at the same path: identical means the upstream version can simply be copied. */
    private Optional<RiskItem> otherFile(AnalysisContext ctx, ChangedFile file) {
        Optional<String> inTarget = ctx.contentId(Side.TARGET, file.path());
        Location upstream = new Location(file.path(), null, null);
        Location target = inTarget.isPresent() ? upstream : null;
        if (file.change() == ChangedFile.Change.DELETED) {
            return inTarget.map(id -> RiskItem.of(RiskCategory.DELETED_UPSTREAM, ElementKind.FILE, file.path(),
                    UpstreamChange.DELETED, upstream, target, TargetState.UNCHANGED, List.of(), List.of()));
        }
        if (file.change() == ChangedFile.Change.ADDED) {
            return Optional.of(inTarget.isPresent()
                    ? item(RiskLevel.MEDIUM, file, upstream, target, TargetState.MODIFIED,
                    "repo2 already has a file at this path")
                    : item(RiskLevel.LOW, file, upstream, null, TargetState.ABSENT, "new file"));
        }
        if (inTarget.isEmpty()) {
            return Optional.of(item(RiskLevel.MEDIUM, file, upstream, null, TargetState.MISSING,
                    "not at this path in repo2 — moved or deleted by the migration"));
        }
        boolean unchanged = inTarget.equals(ctx.contentId(Side.BASELINE, file.oldPath()));
        return Optional.of(unchanged
                ? item(RiskLevel.LOW, file, upstream, target, TargetState.UNCHANGED, "repo2 still has the baseline version")
                : item(RiskLevel.MEDIUM, file, upstream, target, TargetState.MODIFIED, "repo2 changed this file too"));
    }

    private static RiskItem item(RiskLevel level, ChangedFile file, Location upstream, Location target,
                                 TargetState state, String detail) {
        return RiskItem.of(level, RiskCategory.NON_JAVA_FILE, ElementKind.FILE, file.path(), change(file),
                upstream, target, state, List.of(), List.of(detail));
    }

    /** Only lines repo1 added since the baseline: code repo2 already migrated is not flagged again. */
    private List<RiskItem> apiUsage(AnalysisContext ctx, ChangedFile file) {
        if (file.change() == ChangedFile.Change.DELETED || file.addedLines().isEmpty() || rules.isEmpty()) {
            return List.of();
        }
        Optional<String> text = ctx.text(Side.HEAD, file.newPath());
        if (text.isEmpty()) {
            return List.of();
        }
        String[] lines = text.get().split("\\R", -1);
        List<RiskItem> items = new ArrayList<>();
        for (CompiledRule rule : rules) {
            List<String> matches = new ArrayList<>();
            Integer first = null;
            int count = 0;
            for (int line : file.addedLines()) {
                if (line <= lines.length && rule.pattern().matcher(lines[line - 1]).find()) {
                    first = first == null ? line : first;
                    if (++count <= MAX_MATCH_LINES) {
                        matches.add("L" + line + ": " + lines[line - 1].trim());
                    }
                }
            }
            if (count > MAX_MATCH_LINES) {
                matches.add("... " + (count - MAX_MATCH_LINES) + " more");
            }
            if (first != null) {
                String hint = rule.rule().hint() == null ? rule.rule().id() : rule.rule().id() + ": " + rule.rule().hint();
                items.add(RiskItem.of(RiskCategory.MIGRATION_API, ElementKind.FILE,
                        file.newPath() + " [" + rule.rule().id() + "]", change(file),
                        new Location(file.newPath(), first, null), null, null, List.of(hint), matches));
            }
        }
        return items;
    }

    private static UpstreamChange change(ChangedFile file) {
        return switch (file.change()) {
            case ADDED -> UpstreamChange.ADDED;
            case DELETED -> UpstreamChange.DELETED;
            case MODIFIED, RENAMED -> UpstreamChange.MODIFIED;
        };
    }
}
