package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.api.dto.AnalysisRequest;
import com.creed.resource.migrationrisk.api.dto.DirectoryAnalysisRequest;
import com.creed.resource.migrationrisk.api.dto.AnalysisResponse;
import com.creed.resource.migrationrisk.api.dto.AnalysisResponse.ChangedFileView;
import com.creed.resource.migrationrisk.api.dto.AnalysisResponse.CommitView;
import com.creed.resource.migrationrisk.api.dto.AnalysisResponse.Counts;
import com.creed.resource.migrationrisk.api.dto.AnalysisResponse.Source;
import com.creed.resource.migrationrisk.config.MigrationRiskProperties;
import com.creed.resource.migrationrisk.domain.DetectedRefactoring;
import com.creed.resource.migrationrisk.domain.Location;
import com.creed.resource.migrationrisk.domain.RiskCategory;
import com.creed.resource.migrationrisk.domain.RiskItem;
import com.creed.resource.migrationrisk.domain.RiskLevel;
import com.creed.resource.migrationrisk.git.ChangedFile;
import com.creed.resource.migrationrisk.git.GitRepositories;
import com.creed.resource.migrationrisk.git.GitRepositories.Snapshot;
import com.creed.resource.migrationrisk.source.DirectoryTree;
import com.creed.resource.migrationrisk.source.GitTree;
import com.creed.resource.migrationrisk.source.MergedTree;
import com.creed.resource.migrationrisk.source.SourceTree;
import com.creed.resource.migrationrisk.source.TreeDiff;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * repo1 HEAD compared with repo2 directly — repo2 being one repository or two (repo2A + repo2B, read as one tree).
 *
 * <p>No baseline: every difference between the two code bases is reported, the migration's own rewrites included.
 * What keeps that readable: methods and fields are compared on their normalized text (imports, formatting and
 * Javadoc do not count), config key by key across all files, Camel XML routes by route id, a class / file repo2
 * moved is found by RefactoringMiner or by file name, and an API-rule match alone in a file is taken as the
 * migration's rewrite, not reported.
 *
 * <p>Only the files that differ are exported for RefactoringMiner (repo1's version, repo2's, and repo2 files with
 * the same name to catch moved classes): whole repositories make it slow and memory-hungry.
 */
@Service
@Slf4j
public class MigrationRiskService {

    /** Parts that are no work: an API match beside only these is the migration's own rewrite. */
    private static final Set<RiskCategory> NO_WORK = EnumSet.of(RiskCategory.ALREADY_SYNCED, RiskCategory.CONFIG_IN_SYNC,
            RiskCategory.ONLY_IN_REPO2, RiskCategory.MIGRATION_API);

    private final MigrationRiskProperties properties;
    private final RepositoryGuard guard;
    private final RefactoringDetector detector;
    private final JavaRiskAnalyzer javaAnalyzer;
    private final FileRiskAnalyzer fileAnalyzer;
    private final GlobalConfigAnalyzer configAnalyzer;
    private final CamelRouteAnalyzer camelAnalyzer;
    private final IgnoreRules ignoreRules;
    private final RepositoryLocator locator;
    private final Clock clock;

    public MigrationRiskService(MigrationRiskProperties properties, RepositoryGuard guard, RefactoringDetector detector,
                                JavaRiskAnalyzer javaAnalyzer, FileRiskAnalyzer fileAnalyzer,
                                GlobalConfigAnalyzer configAnalyzer, CamelRouteAnalyzer camelAnalyzer,
                                IgnoreRules ignoreRules, RepositoryLocator locator, Clock clock) {
        this.camelAnalyzer = camelAnalyzer;
        this.ignoreRules = ignoreRules;
        this.locator = locator;
        this.configAnalyzer = configAnalyzer;
        this.properties = properties;
        this.guard = guard;
        this.detector = detector;
        this.javaAnalyzer = javaAnalyzer;
        this.fileAnalyzer = fileAnalyzer;
        this.clock = clock;
    }

    /** Git mode: repo1's head and repo2's (and repo2B's). */
    public AnalysisResponse analyze(AnalysisRequest request) {
        return analyze(inputs(request), Progress.NONE);
    }

    public static Inputs inputs(AnalysisRequest r) {
        return Inputs.git(r.repo1Path(), r.repo1Head(), r.repo2Path(), r.repo2Head(), r.repo2bPath(), r.repo2bHead(),
                r.skipTests());
    }

    public static Inputs inputs(DirectoryAnalysisRequest r) {
        return Inputs.folders(r.repo1Path(), r.repo2Path(), r.repo2bPath(), r.skipTests());
    }

    /** Either mode, reporting to {@code progress} (a background job) or to nobody. */
    public AnalysisResponse analyze(Inputs in, Progress progress) {
        long started = System.nanoTime();
        return withTrees(in, progress, trees -> run(trees, started, progress));
    }

    /**
     * The cheap part of an analysis, run before a background job is accepted: paths allowed, repositories
     * open, revisions resolve. A wrong branch is answered at once (with its field), not as a failed job.
     */
    public void validate(Inputs in) {
        if (in.mode() == Inputs.Mode.DIRECTORY) {
            guard.check(in.repo1Path(), "repo1Path");
            guard.check(in.repo2Path(), "repo2Path");
            if (in.repo2bPath() != null) {
                guard.check(in.repo2bPath(), "repo2bPath");
            }
            return;
        }
        Path repo1Dir = locator.repository(in.repo1Path(), "repo1Path");
        Path repo2Dir = locator.repository(in.repo2Path(), "repo2Path");
        Path repo2bDir = in.repo2bPath() == null ? null : locator.repository(in.repo2bPath(), "repo2bPath");
        try (Git repo1 = GitRepositories.open(repo1Dir, "repo1Path"); Git repo2 = GitRepositories.open(repo2Dir, "repo2Path");
             Git repo2b = repo2bDir == null ? null : GitRepositories.open(repo2bDir, "repo2bPath")) {
            GitRepositories.resolve(repo1, in.repo1Head(), "repo1Head");
            GitRepositories.resolve(repo2, in.repo2Head(), "repo2Head");
            if (repo2b != null) {
                GitRepositories.resolve(repo2b, in.repo2bHead(), "repo2bHead");
            }
        }
    }

    /** Folder mode: plain directories, no git needed — what is on disk, uncommitted edits included. */
    public AnalysisResponse analyzeDirectories(DirectoryAnalysisRequest request) {
        return analyze(inputs(request), Progress.NONE);
    }

    /**
     * Opens the versions an analysis compares, computes the files that differ, and hands them to {@code work} — git
     * repositories stay open exactly as long as it runs. The analysis and the diff view both start here, so they
     * always look at the same files.
     */
    public <T> T withTrees(Inputs in, Function<Trees, T> work) {
        return withTrees(in, Progress.NONE, work);
    }

    public <T> T withTrees(Inputs in, Progress progress, Function<Trees, T> work) {
        List<String> warnings = new ArrayList<>();
        progress.stage(Progress.Stage.RESOLVE, in.mode() == Inputs.Mode.DIRECTORY ? "reading the folders"
                : "opening repo1 and repo2, resolving revisions");
        if (in.mode() == Inputs.Mode.DIRECTORY) {
            log.info("analysis (folders): repo1={} | repo2={}{}{}", in.repo1Path(), in.repo2Path(),
                    in.repo2bPath() == null ? "" : " + " + in.repo2bPath(), in.skipTests() ? " | no tests" : "");
            Path repo1Dir = guard.check(in.repo1Path(), "repo1Path");
            Path repo2Dir = guard.check(in.repo2Path(), "repo2Path");
            Path repo2bDir = in.repo2bPath() == null ? null : guard.check(in.repo2bPath(), "repo2bPath");
            Set<String> excludes = Set.copyOf(properties.directoryExcludes());
            DirectoryTree repo1 = new DirectoryTree(repo1Dir, excludes);
            SourceTree repo2 = target(new DirectoryTree(repo2Dir, excludes),
                    repo2bDir == null ? null : new DirectoryTree(repo2bDir, excludes), warnings);
            if (repo1Dir.equals(repo2Dir)) {
                warnings.add("repo1 and repo2 are the same folder: there is nothing to compare");
            }
            Source source = new Source("DIRECTORY", repo1Dir.toString(), repo2Dir.toString(), null, null,
                    repo2bDir == null ? null : repo2bDir.toString(), null, in.skipTests());
            return work.apply(new Trees(repo1, repo2, changes(in, repo1, repo2, progress, warnings), warnings, source));
        }
        log.info("analysis (git): repo1={} head={} | repo2={} head={}{}{}", in.repo1Path(), in.repo1Head(),
                in.repo2Path(), in.repo2Head(),
                in.repo2bPath() == null ? "" : " + " + in.repo2bPath() + " head=" + in.repo2bHead(),
                in.skipTests() ? " | no tests" : "");
        Path repo1Dir = locator.repository(in.repo1Path(), "repo1Path");
        Path repo2Dir = locator.repository(in.repo2Path(), "repo2Path");
        Path repo2bDir = in.repo2bPath() == null ? null : locator.repository(in.repo2bPath(), "repo2bPath");
        // A null resource is legal in try-with-resources (not closed): repo2B is optional.
        try (Git repo1 = GitRepositories.open(repo1Dir, "repo1Path"); Git repo2 = GitRepositories.open(repo2Dir, "repo2Path");
             Git repo2b = repo2bDir == null ? null : GitRepositories.open(repo2bDir, "repo2bPath")) {
            Snapshot head = GitRepositories.resolve(repo1, in.repo1Head(), "repo1Head");
            Snapshot target = GitRepositories.resolve(repo2, in.repo2Head(), "repo2Head");
            Snapshot targetB = repo2b == null ? null : GitRepositories.resolve(repo2b, in.repo2bHead(), "repo2bHead");
            // a URL stays a URL: saved runs and the diff view resolve (and fetch) it again
            Source source = new Source("GIT", locator.display(in.repo1Path(), repo1Dir),
                    locator.display(in.repo2Path(), repo2Dir), commit(in.repo1Head(), head.commit()),
                    commit(in.repo2Head(), target.commit()),
                    repo2bDir == null ? null : locator.display(in.repo2bPath(), repo2bDir),
                    targetB == null ? null : commit(in.repo2bHead(), targetB.commit()), in.skipTests());
            SourceTree repo1Tree = new GitTree(head);
            SourceTree repo2Tree = target(new GitTree(target), targetB == null ? null : new GitTree(targetB), warnings);
            return work.apply(new Trees(repo1Tree, repo2Tree, changes(in, repo1Tree, repo2Tree, progress, warnings),
                    warnings, source));
        }
    }

    /** Every file that differs, repo2 -> repo1 (old = repo2's path, new = repo1's). */
    private static List<ChangedFile> changes(Inputs in, SourceTree repo1, SourceTree repo2, Progress progress,
                                             List<String> warnings) {
        progress.stage(Progress.Stage.DIFF, "comparing " + repo1.paths().size() + " repo1 files with "
                + repo2.paths().size() + " repo2 files");
        List<ChangedFile> changes = withoutTests(in, TreeDiff.diff(repo2, repo1), warnings);
        log.info("repo1 vs repo2: {} files differ ({} Java) out of {} / {} files", changes.size(),
                changes.stream().filter(ChangedFile::isJava).count(), repo1.paths().size(), repo2.paths().size());
        return changes;
    }

    /** repo2 as the analysis reads it: one tree, or repo2A + repo2B merged into one. */
    private static SourceTree target(SourceTree repo2, SourceTree repo2b, List<String> warnings) {
        if (repo2b == null) {
            return repo2;
        }
        MergedTree merged = new MergedTree(List.of(new MergedTree.Member(REPO2A, repo2), new MergedTree.Member(REPO2B, repo2b)));
        if (!merged.collisions().isEmpty()) {
            warnings.add(merged.collisions().size() + " path(s) exist in both repo2A and repo2B; repo2A's copy is"
                    + " compared, e.g. " + String.join(", ", merged.collisions().stream().limit(3).toList()));
        }
        log.info("repo2 = repo2A ({} files) + repo2B ({} files), {} paths in both", repo2.paths().size(),
                repo2b.paths().size(), merged.collisions().size());
        return merged;
    }

    /** Test code out of the change set — and with it out of RefactoringMiner's input, which is most of the time. */
    private static List<ChangedFile> withoutTests(Inputs in, List<ChangedFile> changes, List<String> warnings) {
        if (!in.skipTests()) {
            return changes;
        }
        List<ChangedFile> kept = changes.stream()
                .filter(f -> !CodeTags.isTest(f.newPath()) && !CodeTags.isTest(f.oldPath())).toList();
        if (kept.size() < changes.size()) {
            warnings.add((changes.size() - kept.size()) + " changed test file(s) skipped (skipTests)");
        }
        return kept;
    }

    public static final String REPO2A = "repo2A";
    public static final String REPO2B = "repo2B";

    /** What to compare: two git revisions (repo1, repo2), or two folders — repo2 optionally being two ({@code repo2bPath}). */
    public record Inputs(Mode mode, String repo1Path, String repo1Head, String repo2Path, String repo2Head,
                         String repo2bPath, String repo2bHead, boolean skipTests) {

        public enum Mode { GIT, DIRECTORY }

        public Inputs {
            repo2bPath = repo2bPath == null || repo2bPath.isBlank() ? null : repo2bPath.trim();
            repo2bHead = repo2bPath == null || mode == Mode.DIRECTORY ? null
                    : repo2bHead == null || repo2bHead.isBlank() ? "HEAD" : repo2bHead.trim();
        }

        public static Inputs git(String repo1Path, String repo1Head, String repo2Path, String repo2Head,
                                 String repo2bPath, String repo2bHead, boolean skipTests) {
            return new Inputs(Mode.GIT, repo1Path, repo1Head, repo2Path, repo2Head, repo2bPath, repo2bHead, skipTests);
        }

        public static Inputs folders(String repo1Path, String repo2Path, String repo2bPath, boolean skipTests) {
            return new Inputs(Mode.DIRECTORY, repo1Path, null, repo2Path, null, repo2bPath, null, skipTests);
        }
    }

    /** The opened versions and the files that differ between them. */
    public record Trees(SourceTree repo1, SourceTree repo2, List<ChangedFile> changes, List<String> warnings,
                        Source source) {
    }

    private AnalysisResponse run(Trees trees, long started, Progress progress) {
        SourceTree repo1 = trees.repo1();
        SourceTree repo2 = trees.repo2();
        List<ChangedFile> changes = trees.changes();
        List<String> warnings = trees.warnings();
        Source source = trees.source();
        if (changes.size() > properties.maxChangedFiles()) {
            throw new TooManyChangesException(changes.size(), properties.maxChangedFiles());
        }
        if (changes.isEmpty()) {
            warnings.add("repo1 and repo2 are identical");
        }
        RefactoringIndex refactorings;
        Path workspace = properties.workDir().resolve(UUID.randomUUID().toString());
        try {
            refactorings = new RefactoringIndex(detect(workspace, repo1, repo2, changes, warnings, progress));
        } finally {
            FileSystemUtils.deleteRecursively(workspace.toFile());
        }

        AnalysisContext ctx = new AnalysisContext(repo1, repo2, changes, refactorings, progress, source.skipTests());
        List<RiskItem> items = new ArrayList<>();
        items.addAll(javaAnalyzer.analyze(ctx));
        CamelRouteAnalyzer.Result camel = camelAnalyzer.analyze(ctx, warnings);
        items.addAll(camel.items());
        items.addAll(fileAnalyzer.analyze(ctx, camel.files()));
        items.addAll(configAnalyzer.analyze(ctx, warnings));
        items = withoutLoneApiMatches(items, warnings);
        items.sort(Comparator.comparing(RiskItem::level).thenComparing(RiskItem::category)
                .thenComparing(RiskItem::element));
        items = FileRecords.group(tag(dedupe(items), repo1, repo2), changes);
        Set<String> reported = new HashSet<>(items.stream().map(RiskItem::element).toList());
        long moved = changes.stream().filter(f -> f.change() == ChangedFile.Change.RENAMED).count();
        long silent = changes.stream().filter(f -> f.change() != ChangedFile.Change.RENAMED)
                .filter(f -> !reported.contains(f.path())).count();
        if (moved > 0) {
            warnings.add(moved + " file(s) only moved, content identical: not reported");
        }
        if (silent > 0) {
            warnings.add(silent + " differing file(s) have nothing to report: imports, formatting, comments, Camel"
                    + " routes kept in another file, or the migration's own API rewrite only");
        }
        items = ignoreRules.apply(items, source.repo1Path(), warnings);

        long millis = (System.nanoTime() - started) / 1_000_000;
        log.info("{} vs {}: {} differing files, {} refactorings, {} items in {} ms",
                Path.of(source.repo1Path()).getFileName(), Path.of(source.repo2Path()).getFileName(), changes.size(),
                refactorings.refactorings().size(), items.size(), millis);
        return new AnalysisResponse(null, null, source, counts(items), items, refactorings.refactorings(),
                changes.stream().map(this::view).toList(), warnings, millis, Instant.now(clock));
    }

    /**
     * An API-rule match is the old API on repo1's side of a difference. Next to another difference in the file it is a
     * porting hint; alone (a file whose only difference is {@code javax -> jakarta} imports, say) it is the
     * migration's own rewrite, and reporting it would flag every migrated file.
     */
    private static List<RiskItem> withoutLoneApiMatches(List<RiskItem> items, List<String> warnings) {
        Set<String> work = new HashSet<>();
        items.stream().filter(i -> !NO_WORK.contains(i.category())).map(RiskItem::path).forEach(work::add);
        List<RiskItem> kept = new ArrayList<>();
        int dropped = 0;
        for (RiskItem i : items) {
            if (i.category() == RiskCategory.MIGRATION_API && !work.contains(i.path())) {
                dropped++;
            } else {
                kept.add(i);
            }
        }
        if (dropped > 0) {
            log.info("{} API-rule match(es) alone in their file: taken as the migration's rewrite", dropped);
        }
        return kept;
    }

    /** Module + test tags from the path; with two repo2s, which one each target was found in. */
    private static List<RiskItem> tag(List<RiskItem> items, SourceTree repo1, SourceTree repo2) {
        Set<String> paths = new LinkedHashSet<>(repo1.paths());
        paths.addAll(repo2.paths()); // a file only repo2 has still belongs to a module
        CodeTags tags = new CodeTags(paths);
        return items.stream().map(i -> {
            Location t = i.target();
            if (t != null && repo2 instanceof MergedTree merged) {
                t = t.inRepo(merged.origin(t.path()).orElse(null));
            }
            return i.tagged(tags.module(i.path()), CodeTags.isTest(i.path()), t);
        }).toList();
    }

    /** Exports the differing Java files of both sides and runs RefactoringMiner repo1 -> repo2 on them. */
    private List<DetectedRefactoring> detect(Path workspace, SourceTree repo1, SourceTree repo2,
                                             List<ChangedFile> changes, List<String> warnings, Progress progress) {
        long java = changes.stream().filter(ChangedFile::isJava).count();
        if (java == 0) {
            return List.of();
        }
        progress.stage(Progress.Stage.EXPORT, "exporting " + java + " differing Java files and their repo2 candidates");
        Exported exported = export(workspace, repo1, repo2, changes, warnings);
        progress.stage(Progress.Stage.REFACTORINGS, "RefactoringMiner: repo1 -> repo2 (" + java + " files)");
        return detector.detect("repo1 -> repo2", exported.repo1(), exported.repo2());
    }

    /** The two comparison directories under one workspace. */
    public record Exported(Path repo1, Path repo2) {
    }

    /**
     * Writes what RefactoringMiner compares: repo1's version of every differing Java file, and repo2's — the same
     * path, a file only repo2 has, and repo2 files with the same name as a repo1 one (to catch moved classes).
     */
    public Exported export(Path workspace, SourceTree repo1, SourceTree repo2, List<ChangedFile> changes,
                           List<String> warnings) {
        Map<String, List<String>> repo2ByName = repo2.pathsByFileName();
        Set<String> repo1Paths = new LinkedHashSet<>();
        Set<String> repo2Paths = new LinkedHashSet<>();
        for (ChangedFile f : changes.stream().filter(ChangedFile::isJava).toList()) {
            if (f.oldPath() != null) {
                repo2Paths.add(f.oldPath());
            }
            if (f.newPath() == null) {
                continue;
            }
            repo1Paths.add(f.newPath());
            String name = f.newPath().substring(f.newPath().lastIndexOf('/') + 1);
            List<String> sameName = repo2ByName.getOrDefault(name, List.of());
            if (sameName.size() > properties.maxCandidatesPerName()) {
                warnings.add(sameName.size() + " files named " + name + " in repo2; only "
                        + properties.maxCandidatesPerName() + " compared for a moved class");
            }
            sameName.stream().limit(properties.maxCandidatesPerName()).forEach(repo2Paths::add);
        }
        Path left = mkdirs(workspace.resolve("repo1"));
        Path right = mkdirs(workspace.resolve("repo2"));
        repo1.export(repo1Paths, left);
        repo2.export(repo2Paths, right);
        log.info("exported {} repo1 / {} repo2 paths to {}", repo1Paths.size(), repo2Paths.size(), workspace);
        return new Exported(left, right);
    }

    /** The same element can surface twice (e.g. a moved method found twice): keep the riskier. */
    private static List<RiskItem> dedupe(List<RiskItem> sorted) {
        Set<String> seen = new LinkedHashSet<>();
        return sorted.stream().filter(i -> seen.add(i.id())).toList();
    }

    private static Counts counts(List<RiskItem> items) {
        return new Counts(items.size(), count(items, RiskLevel.HIGH), count(items, RiskLevel.MEDIUM),
                count(items, RiskLevel.LOW));
    }

    private static int count(List<RiskItem> items, RiskLevel level) {
        return (int) items.stream().filter(i -> i.level() == level).count();
    }

    static CommitView commit(String revision, RevCommit c) {
        return new CommitView(revision, c.getName(), c.abbreviate(10).name(), c.getShortMessage(),
                c.getAuthorIdent().getName(), Instant.ofEpochSecond(c.getCommitTime()));
    }

    private ChangedFileView view(ChangedFile f) {
        String kind = f.isJava() ? "JAVA" : ConfigDiff.isPom(f.path()) ? "POM"
                : configAnalyzer.handles(f.path()) ? "CONFIG" : "OTHER";
        return new ChangedFileView(f.change().name(), f.oldPath(), f.newPath(), kind, f.addedLines().size());
    }

    private static Path mkdirs(Path dir) {
        try {
            return Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static class TooManyChangesException extends RuntimeException {
        public TooManyChangesException(int changed, int max) {
            super(changed + " files differ between repo1 and repo2 (limit " + max
                    + ", migration-risk.max-changed-files): narrow it with skipTests or an ignore rule, or raise the limit");
        }
    }
}
