package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.config.MigrationRiskProperties;
import com.creed.resource.migrationrisk.config.MigrationRiskProperties.ApiRule;
import com.creed.resource.migrationrisk.domain.ElementKind;
import com.creed.resource.migrationrisk.domain.Location;
import com.creed.resource.migrationrisk.domain.RiskCategory;
import com.creed.resource.migrationrisk.domain.RiskItem;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Everything RefactoringMiner does not look at: pom.xml (dependency by dependency), templates repo2 keeps in another
 * format, every other non-Java file (line diff; Camel route XML is {@link CamelRouteAnalyzer}'s), and migration-sensitive API usage in repo1's lines that repo2 lacks
 * (any file type). Properties / yml are left to {@link GlobalConfigAnalyzer}, which compares keys across all files.
 *
 * <p>Details lines read like {@code git diff repo1 repo2}, the same direction as the diff page (repo1 left, repo2
 * right): {@code - L4: } is repo1's line 4 (repo2 lacks it), {@code + L4: } is repo2's line 4 (repo1 lacks it).
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

    /** {@code camelFiles}: Camel route files {@link CamelRouteAnalyzer} compared by route id — no line diff for them. */
    public List<RiskItem> analyze(AnalysisContext ctx, Set<String> camelFiles) {
        List<RiskItem> items = new ArrayList<>();
        Map<String, Boolean> repo2HasExtension = new HashMap<>();
        // repo2 files that turned out to be a repo1 file's counterpart (moved, converted): not "only in repo2"
        Set<String> matched = new HashSet<>();
        int done = 0;
        for (ChangedFile file : ctx.changes()) {
            ctx.progress().step(Progress.Stage.FILES, done++, ctx.changes().size(), file.path());
            items.addAll(apiUsage(ctx, file));
            if (file.isJava() || configs.handles(file.path()) || file.newPath() == null
                    || file.change() == ChangedFile.Change.RENAMED || camelFiles.contains(file.newPath())) {
                continue;
            }
            if (ConfigDiff.isPom(file.path())) {
                pom(ctx, file).ifPresent(items::add);
                continue;
            }
            Optional<RiskItem> converted = converted(ctx, file, repo2HasExtension);
            converted.map(RiskItem::target).filter(t -> t != null).ifPresent(t -> matched.add(t.path()));
            (converted.isPresent() ? converted : otherFile(ctx, file, matched)).ifPresent(items::add);
        }
        for (ChangedFile file : ctx.changes()) {
            if (file.newPath() == null && !file.isJava() && !configs.handles(file.path()) && !matched.contains(file.oldPath())
                    && !camelFiles.contains(file.oldPath())) {
                Location there = new Location(file.oldPath(), null, null);
                items.add(RiskItem.of(RiskCategory.ONLY_IN_REPO2, ElementKind.FILE, file.oldPath(), UpstreamChange.DELETED,
                        null, there, TargetState.EXTRA, List.of(), List.of("only repo2 has this file")));
            }
        }
        return items;
    }

    // ---------------------------------------------------------------- pom.xml

    private Optional<RiskItem> pom(AnalysisContext ctx, ChangedFile file) {
        String path = file.newPath();
        Location here = new Location(path, null, null);
        if (file.oldPath() == null) {
            return Optional.of(RiskItem.of(RiskCategory.TARGET_MISSING, ElementKind.DEPENDENCY, path, UpstreamChange.ADDED,
                    here, null, TargetState.MISSING, List.of(), List.of("repo2 has no pom.xml at this path")));
        }
        List<String> details;
        try {
            details = ConfigDiff.pomLines(ctx.text(Side.REPO1, path).orElse(null), ctx.text(Side.REPO2, file.oldPath()).orElse(null));
        } catch (RuntimeException e) {
            // Unparseable on one side (a broken pom): still HIGH, just without dependency detail.
            details = List.of("could not compare dependencies: " + e.getMessage());
        }
        if (details.isEmpty()) {
            return Optional.empty(); // formatting, comments, or elements outside dependencies / properties / parent
        }
        return Optional.of(RiskItem.of(RiskCategory.DEPENDENCY_CHANGED, ElementKind.DEPENDENCY, path,
                UpstreamChange.MODIFIED, here, new Location(file.oldPath(), null, null), TargetState.MODIFIED,
                List.of(), details));
    }

    // ---------------------------------------------------------------- templates in another format

    /**
     * A file repo2 keeps in another format — {@code templates/invoice.vm} is {@code templates/invoice.ftl} there.
     * repo1's file is drafted in the new syntax ({@link VelocityToFreemarker} for vm -> ftl) and that draft is diffed
     * with repo2's file, whitespace ignored: no difference means repo2 has it, else the lines to port. Empty when no
     * mapping applies or repo2 has the file under its old name (then it is an ordinary file).
     */
    private Optional<RiskItem> converted(AnalysisContext ctx, ChangedFile file, Map<String, Boolean> repo2HasExtension) {
        String path = file.newPath();
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot < path.lastIndexOf('/') || file.oldPath() != null) {
            return Optional.empty();
        }
        String from = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        String to = conversions.get(from);
        if (to == null) {
            return Optional.empty();
        }
        String counterpart = path.substring(0, dot + 1) + to;
        Optional<String> found = ctx.contentId(Side.REPO2, counterpart).isPresent() ? Optional.of(counterpart)
                : closest(ctx.repo2PathsNamed(counterpart.substring(counterpart.lastIndexOf('/') + 1)), counterpart);
        Location here = new Location(path, null, null);
        List<String> evidence = List.of("." + from + " -> ." + to + " (migration-risk.extension-mappings)");
        String mine = ctx.text(Side.REPO1, path).orElse("");
        String draft = draft(from, to, mine);
        if (found.isPresent()) {
            List<String> diff = lineDiff(draft, 1, ctx.text(Side.REPO2, found.get()).orElse(""), 1,
                    RawTextComparator.WS_IGNORE_ALL);
            Location there = new Location(found.get(), null, null);
            if (diff.isEmpty()) {
                return Optional.of(RiskItem.of(RiskCategory.ALREADY_SYNCED, ElementKind.FILE, path,
                        UpstreamChange.MODIFIED, here, there, TargetState.RELOCATED, evidence,
                        List.of("repo2's " + found.get() + " matches this file drafted in ." + to + " syntax")));
            }
            List<String> details = new ArrayList<>();
            details.add("repo2 has it as " + found.get() + ": - = this file drafted in ." + to + " syntax, + = repo2's");
            details.addAll(diff);
            return Optional.of(RiskItem.of(RiskCategory.TEMPLATE_CONVERTED, ElementKind.FILE, path,
                    UpstreamChange.MODIFIED, here, there, TargetState.RELOCATED, evidence, details));
        }
        // A template in a format repo2 dropped entirely: it has to be written in the new one.
        boolean converts = repo2HasExtension.computeIfAbsent(to, ext -> hasExtension(ctx, ext))
                && !repo2HasExtension.computeIfAbsent(from, ext -> hasExtension(ctx, ext));
        if (!converts) {
            return Optional.empty();
        }
        List<String> details = new ArrayList<>();
        details.add("repo2 has no ." + from + " files but uses ." + to + ": write it as ." + to + " (draft below)");
        details.addAll(lineDiff(draft, 1, "", 1, RawTextComparator.DEFAULT).stream()
                .map(l -> l.startsWith("- ") ? "≈ " + l.substring(2) : l).toList());
        return Optional.of(RiskItem.of(RiskCategory.TEMPLATE_CONVERTED, ElementKind.FILE, path, UpstreamChange.ADDED,
                here, null, TargetState.MISSING, evidence, details));
    }

    /** repo1's text in the target syntax where a converter exists; unchanged otherwise. */
    private static String draft(String from, String to, String text) {
        if (!from.equals("vm") || !to.equals("ftl")) {
            return text;
        }
        return String.join("\n", VelocityToFreemarker.convert(text.lines().toList())) + "\n";
    }

    private static boolean hasExtension(AnalysisContext ctx, String extension) {
        String suffix = "." + extension;
        return ctx.paths(Side.REPO2).stream().anyMatch(p -> p.toLowerCase(Locale.ROOT).endsWith(suffix));
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

    // ---------------------------------------------------------------- any other file

    /**
     * Same path: the line diff. Only in repo1: the nearest same-named repo2 file (the migration moved it) — identical
     * is synced, different is diffed — else TARGET_MISSING.
     */
    private Optional<RiskItem> otherFile(AnalysisContext ctx, ChangedFile file, Set<String> matched) {
        String path = file.newPath();
        Location here = new Location(path, null, null);
        String mine = ctx.text(Side.REPO1, path).orElse(null);
        if (file.oldPath() != null) {
            String theirs = ctx.text(Side.REPO2, file.oldPath()).orElse(null);
            return Optional.of(RiskItem.of(RiskCategory.NON_JAVA_FILE, ElementKind.FILE, path, UpstreamChange.MODIFIED,
                    here, new Location(file.oldPath(), null, null), TargetState.MODIFIED, List.of(),
                    mine == null || theirs == null ? List.of("binary file: content differs")
                            : lineDiff(mine, 1, theirs, 1, RawTextComparator.DEFAULT)));
        }
        Optional<String> moved = closest(ctx.repo2PathsNamed(path.substring(path.lastIndexOf('/') + 1)), path)
                .filter(p -> !ctx.paths(Side.REPO1).contains(p)); // repo1's own file at that path is no move
        if (moved.isEmpty()) {
            return Optional.of(RiskItem.of(RiskCategory.TARGET_MISSING, ElementKind.FILE, path, UpstreamChange.ADDED,
                    here, null, TargetState.MISSING, List.of(), List.of("repo2 has no file with this name")));
        }
        matched.add(moved.get());
        Location there = new Location(moved.get(), null, null);
        List<String> evidence = List.of("same file name in repo2: " + moved.get());
        if (ctx.contentId(Side.REPO1, path).equals(ctx.contentId(Side.REPO2, moved.get()))) {
            return Optional.of(RiskItem.of(RiskCategory.ALREADY_SYNCED, ElementKind.FILE, path, UpstreamChange.MODIFIED,
                    here, there, TargetState.RELOCATED, evidence, List.of("repo2 has it at " + moved.get() + ", identical")));
        }
        String theirs = ctx.text(Side.REPO2, moved.get()).orElse(null);
        return Optional.of(RiskItem.of(RiskCategory.NON_JAVA_FILE, ElementKind.FILE, path, UpstreamChange.MODIFIED,
                here, there, TargetState.RELOCATED, evidence,
                mine == null || theirs == null ? List.of("binary file: content differs")
                        : lineDiff(mine, 1, theirs, 1, RawTextComparator.DEFAULT)));
    }

    // ---------------------------------------------------------------- line diffs

    /** {@code - L4: repo1's line} / {@code + L4: repo2's line} per changed line (histogram diff), capped. */
    static List<String> lineDiff(String repo1, int repo1FirstLine, String repo2, int repo2FirstLine) {
        return lineDiff(repo1, repo1FirstLine, repo2, repo2FirstLine, RawTextComparator.DEFAULT);
    }

    static List<String> lineDiff(String repo1, int repo1FirstLine, String repo2, int repo2FirstLine,
                                 RawTextComparator comparator) {
        RawText a = new RawText(repo1.getBytes(StandardCharsets.UTF_8));
        RawText b = new RawText(repo2.getBytes(StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>();
        int count = 0;
        for (Edit edit : new HistogramDiff().diff(comparator, a, b)) {
            for (int i = edit.getBeginA(); i < edit.getEndA(); i++) {
                if (count++ < MAX_MATCH_LINES) {
                    lines.add("- L" + (repo1FirstLine + i) + ": " + a.getString(i).strip());
                }
            }
            for (int i = edit.getBeginB(); i < edit.getEndB(); i++) {
                if (count++ < MAX_MATCH_LINES) {
                    lines.add("+ L" + (repo2FirstLine + i) + ": " + b.getString(i).strip());
                }
            }
        }
        if (count > MAX_MATCH_LINES) {
            lines.add("... " + (count - MAX_MATCH_LINES) + " more lines");
        }
        return lines;
    }

    // ---------------------------------------------------------------- API rules

    /** Only repo1's lines that repo2's same-path file lacks (every line of a file only repo1 has). */
    private List<RiskItem> apiUsage(AnalysisContext ctx, ChangedFile file) {
        if (file.newPath() == null || file.addedLines().isEmpty() || rules.isEmpty()) {
            return List.of();
        }
        Optional<String> text = ctx.text(Side.REPO1, file.newPath());
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
                        file.newPath() + " [" + rule.rule().id() + "]", UpstreamChange.MODIFIED,
                        new Location(file.newPath(), first, null), null, null, List.of(hint), matches));
            }
        }
        return items;
    }
}
