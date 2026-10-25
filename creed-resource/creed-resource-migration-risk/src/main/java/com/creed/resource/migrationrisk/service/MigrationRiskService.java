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
import com.creed.resource.migrationrisk.domain.RiskItem;
import com.creed.resource.migrationrisk.domain.RiskLevel;
import com.creed.resource.migrationrisk.git.ChangedFile;
import com.creed.resource.migrationrisk.git.GitRepositories;
import com.creed.resource.migrationrisk.git.GitRepositories.Snapshot;
import com.creed.resource.migrationrisk.source.DirectoryDiff;
import com.creed.resource.migrationrisk.source.DirectoryTree;
import com.creed.resource.migrationrisk.source.GitTree;
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
 */
@Service
@Slf4j
public class MigrationRiskService {

    private final MigrationRiskProperties properties;
    private final RepositoryGuard guard;
    private final RefactoringDetector detector;
    private final JavaRiskAnalyzer javaAnalyzer;
    private final FileRiskAnalyzer fileAnalyzer;
    private final Clock clock;

    public MigrationRiskService(MigrationRiskProperties properties, RepositoryGuard guard, RefactoringDetector detector,
                                JavaRiskAnalyzer javaAnalyzer, FileRiskAnalyzer fileAnalyzer, Clock clock) {
        this.properties = properties;
        this.guard = guard;
        this.detector = detector;
        this.javaAnalyzer = javaAnalyzer;
        this.fileAnalyzer = fileAnalyzer;
        this.clock = clock;
    }

    /** Git mode: three commits — repo1's baseline and head, repo2's head. */
    public AnalysisResponse analyze(AnalysisRequest request) {
        long started = System.nanoTime();
        return withTrees(Inputs.git(request.repo1Path(), request.baseline(), request.repo1Head(), request.repo2Path(),
                request.repo2Head()), trees -> run(trees, started));
    }

    /**
     * Folder mode: three plain directories, no git needed — what is on disk, uncommitted edits included.
     * The baseline folder is still required: without it a difference between repo1 and repo2 cannot be told
     * apart as "the migration changed this on purpose" or "repo1 changed this since".
     */
    public AnalysisResponse analyzeDirectories(DirectoryAnalysisRequest request) {
        long started = System.nanoTime();
        return withTrees(Inputs.folders(request.baselinePath(), request.repo1Path(), request.repo2Path()),
                trees -> run(trees, started));
    }

    /**
     * Opens the three versions an analysis compares, computes repo1's changes since the baseline, and hands
     * them to {@code work} — git repositories stay open exactly as long as it runs. The analysis and the diff
     * view both start here, so they always look at the same files.
     */
    public <T> T withTrees(Inputs in, Function<Trees, T> work) {
        List<String> warnings = new ArrayList<>();
        if (in.mode() == Inputs.Mode.DIRECTORY) {
            log.info("analysis (folders): baseline={} | repo1={} | repo2={}", in.baselinePath(), in.repo1Path(),
                    in.repo2Path());
            Path baselineDir = guard.check(in.baselinePath(), "baselinePath");
            Path repo1Dir = guard.check(in.repo1Path(), "repo1Path");
            Path repo2Dir = guard.check(in.repo2Path(), "repo2Path");
            Set<String> excludes = Set.copyOf(properties.directoryExcludes());
            DirectoryTree baseline = new DirectoryTree(baselineDir, excludes);
            DirectoryTree head = new DirectoryTree(repo1Dir, excludes);
            DirectoryTree target = new DirectoryTree(repo2Dir, excludes);
            if (baselineDir.equals(repo1Dir)) {
                warnings.add("the baseline folder is repo1 itself: there is nothing upstream to compare");
            }
            List<ChangedFile> changes = DirectoryDiff.diff(baseline, head);
            log.info("baseline folder -> repo1 folder: {} changed files ({} Java) out of {} / {} files; repo2 has {}",
                    changes.size(), changes.stream().filter(ChangedFile::isJava).count(), baseline.paths().size(),
                    head.paths().size(), target.paths().size());
            Source source = new Source("DIRECTORY", baselineDir.toString(), repo1Dir.toString(), repo2Dir.toString(),
                    null, null, null);
            return work.apply(new Trees(baseline, head, target, changes, warnings, source));
        }
        log.info("analysis (git): repo1={} baseline={} head={} | repo2={} head={}", in.repo1Path(), in.baseline(),
                in.repo1Head(), in.repo2Path(), in.repo2Head());
        Path repo1Dir = guard.check(in.repo1Path(), "repo1Path");
        Path repo2Dir = guard.check(in.repo2Path(), "repo2Path");
        try (Git repo1 = GitRepositories.open(repo1Dir, "repo1Path"); Git repo2 = GitRepositories.open(repo2Dir, "repo2Path")) {
            Snapshot baseline = GitRepositories.resolve(repo1, in.baseline(), "baseline");
            Snapshot head = GitRepositories.resolve(repo1, in.repo1Head(), "repo1Head");
            Snapshot target = GitRepositories.resolve(repo2, in.repo2Head(), "repo2Head");
            if (!GitRepositories.isAncestor(repo1.getRepository(), baseline.commit(), head.commit())) {
                warnings.add("baseline " + baseline.commit().abbreviate(10).name() + " is not an ancestor of repo1 "
                        + in.repo1Head() + ": the upstream diff includes changes from another branch");
            }
            List<ChangedFile> changes = GitRepositories.diff(repo1.getRepository(), baseline.commit(), head.commit());
            log.info("baseline {} -> repo1 {}: {} changed files ({} Java); repo2 at {}",
                    baseline.commit().abbreviate(10).name(), head.commit().abbreviate(10).name(), changes.size(),
                    changes.stream().filter(ChangedFile::isJava).count(), target.commit().abbreviate(10).name());
            Source source = new Source("GIT", null, repo1Dir.toString(), repo2Dir.toString(),
                    commit(in.baseline(), baseline.commit()), commit(in.repo1Head(), head.commit()),
                    commit(in.repo2Head(), target.commit()));
            return work.apply(new Trees(new GitTree(baseline), new GitTree(head), new GitTree(target), changes,
                    warnings, source));
        }
    }

    /** What to compare: three git revisions over two repositories, or three folders. */
    public record Inputs(Mode mode, String baselinePath, String repo1Path, String baseline, String repo1Head,
                         String repo2Path, String repo2Head) {

        public enum Mode { GIT, DIRECTORY }

        public static Inputs git(String repo1Path, String baseline, String repo1Head, String repo2Path, String repo2Head) {
            return new Inputs(Mode.GIT, null, repo1Path, baseline, repo1Head, repo2Path, repo2Head);
        }

        public static Inputs folders(String baselinePath, String repo1Path, String repo2Path) {
            return new Inputs(Mode.DIRECTORY, baselinePath, repo1Path, null, null, repo2Path, null);
        }
    }

    /** The three opened versions and repo1's changes between the first two. */
    public record Trees(SourceTree baseline, SourceTree head, SourceTree target, List<ChangedFile> changes,
                        List<String> warnings, Source source) {
    }

    private AnalysisResponse run(Trees trees, long started) {
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
            Runs runs = detect(workspace, baseline, head, target, changes, warnings);
            upstream = new RefactoringIndex(runs.upstream());
            migration = new RefactoringIndex(runs.migration());
        } finally {
            FileSystemUtils.deleteRecursively(workspace.toFile());
        }

        AnalysisContext ctx = new AnalysisContext(baseline, head, target, changes, target.pathsByFileName(),
                upstream, migration);
        List<RiskItem> items = new ArrayList<>();
        items.addAll(javaAnalyzer.analyze(ctx));
        items.addAll(fileAnalyzer.analyze(ctx));
        items.sort(Comparator.comparing(RiskItem::level).thenComparing(RiskItem::category)
                .thenComparing(RiskItem::element));
        items = dedupe(items);

        long millis = (System.nanoTime() - started) / 1_000_000;
        log.info("{} vs {}: {} changed files, {} upstream / {} migration refactorings, {} items in {} ms",
                Path.of(source.repo1Path()).getFileName(), Path.of(source.repo2Path()).getFileName(), changes.size(),
                upstream.refactorings().size(), migration.refactorings().size(), items.size(), millis);
        return new AnalysisResponse(null, null, source, counts(items), items, upstream.refactorings(), migration.refactorings(),
                changes.stream().map(MigrationRiskService::view).toList(), warnings, millis, Instant.now(clock));
    }

    /**
     * Exports the three comparison trees and runs RefactoringMiner on (before, upstream) and
     * (before, target). Both runs share {@code before}, so their "before" locations are the same code.
     */
    private Runs detect(Path workspace, SourceTree baseline, SourceTree head, SourceTree target,
                        List<ChangedFile> changes, List<String> warnings) {
        List<ChangedFile> java = changes.stream().filter(ChangedFile::isJava).toList();
        if (java.isEmpty()) {
            return new Runs(List.of(), List.of());
        }
        Exported exported = export(workspace, baseline, head, target, changes, warnings);
        Path before = exported.before();
        Path upstream = exported.upstream();
        Path migrated = exported.target();
        return new Runs(detector.detect("A upstream", before, upstream), detector.detect("B migration", before, migrated));
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

    private static ChangedFileView view(ChangedFile f) {
        String kind = f.isJava() ? "JAVA" : ConfigDiff.isPom(f.path()) ? "POM"
                : ConfigDiff.isSpringConfig(f.path()) ? "CONFIG" : "OTHER";
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
