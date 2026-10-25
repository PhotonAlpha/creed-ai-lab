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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Everything RefactoringMiner does not look at: Spring config, pom.xml, every other non-Java file, and
 * migration-sensitive API usage in the lines repo1 added (any file type).
 */
@Component
public class FileRiskAnalyzer {

    /** Per rule and file; more matches are summarised, not listed. */
    private static final int MAX_MATCH_LINES = 20;

    private record CompiledRule(ApiRule rule, Pattern pattern) {
    }

    private final List<CompiledRule> rules;

    public FileRiskAnalyzer(MigrationRiskProperties properties) {
        this.rules = properties.apiRules().stream().map(r -> new CompiledRule(r, r.compiled())).toList();
    }

    public List<RiskItem> analyze(AnalysisContext ctx) {
        List<RiskItem> items = new ArrayList<>();
        int done = 0;
        for (ChangedFile file : ctx.changes()) {
            ctx.progress().step(Progress.Stage.FILES, done++,
                    ctx.changes().size(), file.path());
            items.addAll(apiUsage(ctx, file));
            if (file.isJava()) {
                continue;
            }
            if (ConfigDiff.isPom(file.path())) {
                items.add(config(ctx, file, RiskCategory.DEPENDENCY_CHANGED, ElementKind.DEPENDENCY));
            } else if (ConfigDiff.isSpringConfig(file.path())) {
                items.add(config(ctx, file, RiskCategory.CONFIG_CHANGED, ElementKind.CONFIG));
            } else {
                otherFile(ctx, file).ifPresent(items::add);
            }
        }
        return items;
    }

    private RiskItem config(AnalysisContext ctx, ChangedFile file, RiskCategory category, ElementKind kind) {
        String before = ctx.text(Side.BASELINE, file.oldPath()).orElse(null);
        String after = ctx.text(Side.HEAD, file.newPath()).orElse(null);
        String target = ctx.text(Side.TARGET, file.path()).orElse(null);
        List<String> details;
        try {
            details = kind == ElementKind.DEPENDENCY
                    ? ConfigDiff.pomLines(before, after, target)
                    : ConfigDiff.configLines(file.path(), before, after, target);
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
