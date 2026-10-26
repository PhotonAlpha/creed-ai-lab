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
import com.creed.resource.migrationrisk.domain.RiskItem;
import com.creed.resource.migrationrisk.domain.RiskLevel;
import com.creed.resource.migrationrisk.git.ChangedFile;
import com.creed.resource.migrationrisk.git.GitRepositories;
import com.creed.resource.migrationrisk.git.GitRepositories.Snapshot;
import com.creed.resource.migrationrisk.source.DirectoryDiff;
import com.creed.resource.migrationrisk.source.DirectoryTree;
import com.creed.resource.migrationrisk.source.GitTree;
import com.creed.resource.migrationrisk.source.MergedTree;
import com.creed.resource.migrationrisk.source.SourceTree;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Baseline + two RefactoringMiner runs + cross-check.
 *
 * <p>Comparing repo1@HEAD with repo2@HEAD directly would mix two kinds of difference — what the migration
 * changed on purpose (every Jackson import, every Camel 4 API) and what the other team committed since —
 * and only the second needs syncing. Anchoring both runs on repo1@baseline separates them: the upstream
 * run says what to sync, the migration run says where each of those things went.
 *
 * <p>Only the files repo1 changed (plus repo2's files at the same paths, plus repo2 files with the same
 * name to catch moved classes) are exported for RefactoringMiner: whole repositories make it slow and
 * memory-hungry, and nothing outside the change set can affect the answer.
 *
 * <p>repo2 may be two repositories (the migration split repo1): repo2A and repo2B are then read as one
 * {@link MergedTree}, so every lookup searches both and each item's {@code target.repo} says where it was found.
 * Config is compared key by key across all files ({@link GlobalConfigAnalyzer}), and test code can be left out of
 * the whole comparison ({@code skipTests}).
 */
@Service
@Slf4j
public class MigrationRiskService {

    private final MigrationRiskProperties properties;
    private final RepositoryGuard guard;
    private final RefactoringDetector detector;
    private final JavaRiskAnalyzer javaAnalyzer;
    private final FileRiskAnalyzer fileAnalyzer;
    private final GlobalConfigAnalyzer configAnalyzer;
    private final SyncDetector syncDetector;
    private final IgnoreRules ignoreRules;
    private final RepositoryLocator locator;
    private final Clock clock;

    public MigrationRiskService(MigrationRiskProperties properties, RepositoryGuard guard, RefactoringDetector detector,
                                JavaRiskAnalyzer javaAnalyzer, FileRiskAnalyzer fileAnalyzer,
                                GlobalConfigAnalyzer configAnalyzer, SyncDetector syncDetector, IgnoreRules ignoreRules,
                                RepositoryLocator locator, Clock clock) {
        this.ignoreRules = ignoreRules;
        this.locator = locator;
        this.configAnalyzer = configAnalyzer;
        this.syncDetector = syncDetector;
        this.properties = properties;
        this.guard = guard;
        this.detector = detector;
        this.javaAnalyzer = javaAnalyzer;
        this.fileAnalyzer = fileAnalyzer;
        this.clock = clock;
    }

    /** Git mode: three commits — repo1's baseline and head, repo2's head. */
    public AnalysisResponse analyze(AnalysisRequest request) {
        return analyze(inputs(request), Progress.NONE);
    }

    public static Inputs inputs(AnalysisRequest r) {
        return Inputs.git(r.repo1Path(), r.baseline(), r.repo1Head(), r.repo2Path(), r.repo2Head(), r.repo2bPath(),
                r.repo2bHead(), r.skipTests());
    }

    public static Inputs inputs(DirectoryAnalysisRequest r) {
        return Inputs.folders(r.baselinePath(), r.repo1Path(), r.repo2Path(), r.repo2bPath(), r.skipTests());
    }

    /** Either mode, reporting to {@code progress} (a background job) or to nobody. */
    public AnalysisResponse analyze(Inputs in, Progress progress) {
        long started = System.nanoTime();
        return withTrees(in, progress, trees -> run(trees, started, progress));
    }

    /**
     * The cheap part of an analysis, run before a background job is accepted: paths allowed, repositories
     * open, revisions resolve. A wrong baseline is answered at once (with its field), not as a failed job.
     */
    public void validate(Inputs in) {
        if (in.mode() == Inputs.Mode.DIRECTORY) {
            guard.check(in.baselinePath(), "baselinePath");
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
            GitRepositories.resolve(repo1, in.baseline(), "baseline");
            GitRepositories.resolve(repo1, in.repo1Head(), "repo1Head");
            GitRepositories.resolve(repo2, in.repo2Head(), "repo2Head");
            if (repo2b != null) {
                GitRepositories.resolve(repo2b, in.repo2bHead(), "repo2bHead");
            }
        }
    }

    /**
     * Folder mode: three plain directories, no git needed — what is on disk, uncommitted edits included.
     * The baseline folder is still required: without it a difference between repo1 and repo2 cannot be told
     * apart as "the migration changed this on purpose" or "repo1 changed this since".
     */
    public AnalysisResponse analyzeDirectories(DirectoryAnalysisRequest request) {
        return analyze(inputs(request), Progress.NONE);
    }

    /**
     * Opens the three versions an analysis compares, computes repo1's changes since the baseline, and hands
     * them to {@code work} — git repositories stay open exactly as long as it runs. The analysis and the diff
     * view both start here, so they always look at the same files.
     */
    public <T> T withTrees(Inputs in, Function<Trees, T> work) {
        return withTrees(in, Progress.NONE, work);
    }

    public <T> T withTrees(Inputs in, Progress progress, Function<Trees, T> work) {
        List<String> warnings = new ArrayList<>();
        progress.stage(Progress.Stage.RESOLVE, in.mode() == Inputs.Mode.DIRECTORY ? "reading the three folders"
                : "opening repo1 and repo2, resolving revisions");
        if (in.mode() == Inputs.Mode.DIRECTORY) {
            log.info("analysis (folders): baseline={} | repo1={} | repo2={}{}{}", in.baselinePath(), in.repo1Path(),
                    in.repo2Path(), in.repo2bPath() == null ? "" : " + " + in.repo2bPath(), in.skipTests() ? " | no tests" : "");
            Path baselineDir = guard.check(in.baselinePath(), "baselinePath");
            Path repo1Dir = guard.check(in.repo1Path(), "repo1Path");
            Path repo2Dir = guard.check(in.repo2Path(), "repo2Path");
            Path repo2bDir = in.repo2bPath() == null ? null : guard.check(in.repo2bPath(), "repo2bPath");
            Set<String> excludes = Set.copyOf(properties.directoryExcludes());
            DirectoryTree baseline = new DirectoryTree(baselineDir, excludes);
            DirectoryTree head = new DirectoryTree(repo1Dir, excludes);
            SourceTree target = target(new DirectoryTree(repo2Dir, excludes),
                    repo2bDir == null ? null : new DirectoryTree(repo2bDir, excludes), warnings);
            if (baselineDir.equals(repo1Dir)) {
                warnings.add("the baseline folder is repo1 itself: there is nothing upstream to compare");
            }
            progress.stage(Progress.Stage.DIFF, "comparing " + baseline.paths().size() + " baseline files with "
                    + head.paths().size() + " repo1 files");
            List<ChangedFile> changes = withoutTests(in, DirectoryDiff.diff(baseline, head), warnings);
            log.info("baseline folder -> repo1 folder: {} changed files ({} Java) out of {} / {} files; repo2 has {}",
                    changes.size(), changes.stream().filter(ChangedFile::isJava).count(), baseline.paths().size(),
                    head.paths().size(), target.paths().size());
            Source source = new Source("DIRECTORY", baselineDir.toString(), repo1Dir.toString(), repo2Dir.toString(),
                    null, null, null, repo2bDir == null ? null : repo2bDir.toString(), null, in.skipTests());
            return work.apply(new Trees(baseline, head, target, changes, warnings, source));
        }
        log.info("analysis (git): repo1={} baseline={} head={} | repo2={} head={}{}{}", in.repo1Path(), in.baseline(),
                in.repo1Head(), in.repo2Path(), in.repo2Head(),
                in.repo2bPath() == null ? "" : " + " + in.repo2bPath() + " head=" + in.repo2bHead(),
                in.skipTests() ? " | no tests" : "");
        Path repo1Dir = locator.repository(in.repo1Path(), "repo1Path");
        Path repo2Dir = locator.repository(in.repo2Path(), "repo2Path");
        Path repo2bDir = in.repo2bPath() == null ? null : locator.repository(in.repo2bPath(), "repo2bPath");
        // A null resource is legal in try-with-resources (not closed): repo2B is optional.
        try (Git repo1 = GitRepositories.open(repo1Dir, "repo1Path"); Git repo2 = GitRepositories.open(repo2Dir, "repo2Path");
             Git repo2b = repo2bDir == null ? null : GitRepositories.open(repo2bDir, "repo2bPath")) {
            Snapshot baseline = GitRepositories.resolve(repo1, in.baseline(), "baseline");
            Snapshot head = GitRepositories.resolve(repo1, in.repo1Head(), "repo1Head");
            Snapshot target = GitRepositories.resolve(repo2, in.repo2Head(), "repo2Head");
            Snapshot targetB = repo2b == null ? null : GitRepositories.resolve(repo2b, in.repo2bHead(), "repo2bHead");
            if (!GitRepositories.isAncestor(repo1.getRepository(), baseline.commit(), head.commit())) {
                warnings.add("baseline " + baseline.commit().abbreviate(10).name() + " is not an ancestor of repo1 "
                        + in.repo1Head() + ": the upstream diff includes changes from another branch");
            }
            progress.stage(Progress.Stage.DIFF, "git diff " + baseline.commit().abbreviate(10).name() + ".."
                    + head.commit().abbreviate(10).name());
            List<ChangedFile> changes = withoutTests(in,
                    GitRepositories.diff(repo1.getRepository(), baseline.commit(), head.commit()), warnings);
            log.info("baseline {} -> repo1 {}: {} changed files ({} Java); repo2 at {}",
                    baseline.commit().abbreviate(10).name(), head.commit().abbreviate(10).name(), changes.size(),
                    changes.stream().filter(ChangedFile::isJava).count(), target.commit().abbreviate(10).name());
            // a URL stays a URL: saved runs and the diff view resolve (and fetch) it again
            Source source = new Source("GIT", null, locator.display(in.repo1Path(), repo1Dir),
                    locator.display(in.repo2Path(), repo2Dir),
                    commit(in.baseline(), baseline.commit()), commit(in.repo1Head(), head.commit()),
                    commit(in.repo2Head(), target.commit()),
                    repo2bDir == null ? null : locator.display(in.repo2bPath(), repo2bDir),
                    targetB == null ? null : commit(in.repo2bHead(), targetB.commit()), in.skipTests());
            return work.apply(new Trees(new GitTree(baseline), new GitTree(head),
                    target(new GitTree(target), targetB == null ? null : new GitTree(targetB), warnings), changes,
                    warnings, source));
        }
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

    /**
     * What to compare: three git revisions over two repositories, or three folders — repo2 optionally being two
     * repositories ({@code repo2bPath}, null for one).
     */
    public record Inputs(Mode mode, String baselinePath, String repo1Path, String baseline, String repo1Head,
                         String repo2Path, String repo2Head, String repo2bPath, String repo2bHead, boolean skipTests) {

        public enum Mode { GIT, DIRECTORY }

        public Inputs {
            repo2bPath = repo2bPath == null || repo2bPath.isBlank() ? null : repo2bPath.trim();
            repo2bHead = repo2bPath == null || mode == Mode.DIRECTORY ? null
                    : repo2bHead == null || repo2bHead.isBlank() ? "HEAD" : repo2bHead.trim();
        }

        public static Inputs git(String repo1Path, String baseline, String repo1Head, String repo2Path, String repo2Head,
                                 String repo2bPath, String repo2bHead, boolean skipTests) {
            return new Inputs(Mode.GIT, null, repo1Path, baseline, repo1Head, repo2Path, repo2Head, repo2bPath,
                    repo2bHead, skipTests);
        }

        public static Inputs folders(String baselinePath, String repo1Path, String repo2Path, String repo2bPath,
                                     boolean skipTests) {
            return new Inputs(Mode.DIRECTORY, baselinePath, repo1Path, null, null, repo2Path, null, repo2bPath, null,
                    skipTests);
        }
    }

    /** The three opened versions and repo1's changes between the first two. */
    public record Trees(SourceTree baseline, SourceTree head, SourceTree target, List<ChangedFile> changes,
                        List<String> warnings, Source source) {
    }

    private AnalysisResponse run(Trees trees, long started, Progress progress) {
        SourceTree baseline = trees.baseline();
        SourceTree head = trees.head();
        SourceTree target = trees.target();
        List<ChangedFile> changes = trees.changes();
        List<String> warnings = trees.warnings();
        Source source = trees.source();
        if (changes.size() > properties.maxChangedFiles()) {
            throw new TooManyChangesException(changes.size(), properties.maxChangedFiles());
        }
        if (changes.isEmpty()) {
            warnings.add("repo1 has no changes since the baseline");
        }
        RefactoringIndex upstream;
        RefactoringIndex migration;
        Path workspace = properties.workDir().resolve(UUID.randomUUID().toString());
        try {
            Runs runs = detect(workspace, baseline, head, target, changes, warnings, progress);
            upstream = new RefactoringIndex(runs.upstream());
            migration = new RefactoringIndex(runs.migration());
        } finally {
            FileSystemUtils.deleteRecursively(workspace.toFile());
        }

        AnalysisContext ctx = new AnalysisContext(baseline, head, target, changes, target.pathsByFileName(),
                upstream, migration, progress, source.skipTests());
        List<RiskItem> items = new ArrayList<>();
        items.addAll(javaAnalyzer.analyze(ctx));
        items.addAll(fileAnalyzer.analyze(ctx));
        items.addAll(configAnalyzer.analyze(ctx, warnings));
        items = new ArrayList<>(syncDetector.mark(ctx, items));
        items.sort(Comparator.comparing(RiskItem::level).thenComparing(RiskItem::category)
                .thenComparing(RiskItem::element));
        items = ignoreRules.apply(FileRecords.group(tag(dedupe(items), baseline, head, target), changes),
                source.repo1Path(), warnings);

        long millis = (System.nanoTime() - started) / 1_000_000;
        log.info("{} vs {}: {} changed files, {} upstream / {} migration refactorings, {} items in {} ms",
                Path.of(source.repo1Path()).getFileName(), Path.of(source.repo2Path()).getFileName(), changes.size(),
                upstream.refactorings().size(), migration.refactorings().size(), items.size(), millis);
        return new AnalysisResponse(null, null, source, counts(items), items, upstream.refactorings(), migration.refactorings(),
                changes.stream().map(this::view).toList(), warnings, millis, Instant.now(clock));
    }

    /** Module + test tags from repo1's paths; with two repo2s, which one each target was found in. */
    private static List<RiskItem> tag(List<RiskItem> items, SourceTree baseline, SourceTree head, SourceTree target) {
        Set<String> repo1Paths = new LinkedHashSet<>(head.paths());
        repo1Paths.addAll(baseline.paths());
        CodeTags tags = new CodeTags(repo1Paths);
        return items.stream().map(i -> {
            Location t = i.target();
            if (t != null && target instanceof MergedTree merged) {
                t = t.inRepo(merged.origin(t.path()).orElse(null));
            }
            return i.tagged(tags.module(i.path()), CodeTags.isTest(i.path()), t);
        }).toList();
    }

    /**
     * Exports the three comparison trees and runs RefactoringMiner on (before, upstream) and
     * (before, target). Both runs share {@code before}, so their "before" locations are the same code.
     */
    private Runs detect(Path workspace, SourceTree baseline, SourceTree head, SourceTree target,
                        List<ChangedFile> changes, List<String> warnings, Progress progress) {
        List<ChangedFile> java = changes.stream().filter(ChangedFile::isJava).toList();
        if (java.isEmpty()) {
            return new Runs(List.of(), List.of());
        }
        progress.stage(Progress.Stage.EXPORT, "exporting " + java.size() + " changed Java files and their repo2 candidates");
        Exported exported = export(workspace, baseline, head, target, changes, warnings);
        progress.stage(Progress.Stage.UPSTREAM, "RefactoringMiner A: repo1 baseline -> repo1 head (" + java.size() + " files)");
        List<DetectedRefactoring> upstream = detector.detect("A upstream", exported.before(), exported.upstream());
        progress.stage(Progress.Stage.MIGRATION, "RefactoringMiner B: repo1 baseline -> repo2");
        List<DetectedRefactoring> migration = detector.detect("B migration", exported.before(), exported.target());
        return new Runs(upstream, migration);
    }

    private record Runs(List<DetectedRefactoring> upstream, List<DetectedRefactoring> migration) {
    }

    /** The three comparison directories under one workspace. */
    public record Exported(Path before, Path upstream, Path target) {
    }

    /**
     * Writes what RefactoringMiner compares: the changed Java files at the baseline ({@code before}) and at
     * repo1's head ({@code upstream}), and repo2's files at the same paths plus same-named files elsewhere
     * ({@code target}, to catch moved classes). Whole repositories would make it slow and memory-hungry.
     */
    public Exported export(Path workspace, SourceTree baseline, SourceTree head, SourceTree target,
                           List<ChangedFile> changes, List<String> warnings) {
        Map<String, List<String>> targetByName = target.pathsByFileName();
        Set<String> beforePaths = new LinkedHashSet<>();
        Set<String> upstreamPaths = new LinkedHashSet<>();
        Set<String> targetPaths = new LinkedHashSet<>();
        for (ChangedFile f : changes.stream().filter(ChangedFile::isJava).toList()) {
            if (f.oldPath() != null) {
                beforePaths.add(f.oldPath());
            }
            if (f.newPath() != null) {
                upstreamPaths.add(f.newPath());
            }
            for (String path : new String[]{f.oldPath(), f.newPath()}) {
                if (path == null) {
                    continue;
                }
                targetPaths.add(path); // exported only if repo2 has it
                List<String> sameName = targetByName.getOrDefault(path.substring(path.lastIndexOf('/') + 1), List.of());
                if (sameName.size() > properties.maxCandidatesPerName()) {
                    warnings.add(sameName.size() + " files named " + path.substring(path.lastIndexOf('/') + 1)
                            + " in repo2; only " + properties.maxCandidatesPerName() + " compared for a moved class");
                }
                sameName.stream().limit(properties.maxCandidatesPerName()).forEach(targetPaths::add);
            }
        }
        Path before = mkdirs(workspace.resolve("before"));
        Path upstream = mkdirs(workspace.resolve("upstream"));
        Path migrated = mkdirs(workspace.resolve("target"));
        baseline.export(beforePaths, before);
        head.export(upstreamPaths, upstream);
        target.export(targetPaths, migrated);
        log.info("exported {} baseline / {} upstream / {} repo2 candidate paths to {}", beforePaths.size(),
                upstreamPaths.size(), targetPaths.size(), workspace);
        return new Exported(before, upstream, migrated);
    }

    /** The same element can surface twice (e.g. an upstream refactoring and a modification of it): keep the riskier. */
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
            super(changed + " files changed between the baseline and HEAD (limit " + max
                    + ", migration-risk.max-changed-files): choose a later baseline");
        }
    }
}
