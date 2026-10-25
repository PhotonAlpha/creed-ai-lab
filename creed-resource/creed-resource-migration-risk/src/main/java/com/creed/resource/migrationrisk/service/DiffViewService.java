package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.config.MigrationRiskProperties;
import com.creed.resource.migrationrisk.service.MigrationRiskService.Exported;
import com.creed.resource.migrationrisk.service.MigrationRiskService.Inputs;
import gui.webdiff.dir.DirComparator;
import gui.webdiff.viewers.monaco.MonacoCore;
import lombok.extern.slf4j.Slf4j;
import org.refactoringminer.api.Refactoring;
import org.refactoringminer.astDiff.models.ASTDiff;
import org.refactoringminer.astDiff.models.ProjectASTDiff;
import org.refactoringminer.rm1.GitHistoryRefactoringMinerImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * RefactoringMiner's AST diff for one side of an analysis, in the shape its own Monaco viewer renders:
 * side {@code A} = repo1 baseline -> repo1 head (what to sync), side {@code B} = repo1 baseline -> repo2 (where the
 * migration put it). Same file subset as the analysis.
 *
 * <p>The viewer config comes from RefactoringMiner's {@link MonacoCore#makeDiffConfig()} unchanged, so the page
 * shows exactly what RefactoringMiner's web diff shows (moved / updated / deleted / inserted, "moved to file",
 * rename tooltips) — only served by this module instead of its Spark server.
 */
@Service
@Slf4j
public class DiffViewService {

    public enum Side { A, B }

    /**
     * One file in the diff, for navigation. {@code status}: MODIFIED (a pair RefactoringMiner mapped), ADDED (only
     * on the right — a new upstream file on side A) or DELETED (only on the left); the last two have one empty side.
     */
    public record FileDiff(int index, String left, String right, boolean moved, String status) {
    }

    /** All file pairs of one side; {@link #config} renders one of them. */
    public static final class DiffSet {
        private final DirComparator comparator;
        private final ProjectASTDiff project;
        private final List<Refactoring> refactorings;
        private final List<FileDiff> files;
        private final JsonMapper json;

        DiffSet(DirComparator comparator, ProjectASTDiff project, JsonMapper json) {
            this.comparator = comparator;
            this.project = project;
            this.refactorings = project.getRefactorings();
            this.json = json;
            List<FileDiff> list = new ArrayList<>();
            for (int i = 0; i < comparator.getNumOfDiffs(); i++) {
                ASTDiff d = comparator.getASTDiff(i);
                list.add(new FileDiff(i, d.getSrcPath(), d.getDstPath(), comparator.isMoveDiff(i), "MODIFIED"));
            }
            // DirComparator only lists mapped pairs; a file that exists on one side only would be invisible, and
            // on side A every file of a new upstream module is exactly that.
            for (String path : new TreeSet<>(comparator.getAddedFilesName())) {
                list.add(new FileDiff(list.size(), null, path, false, "ADDED"));
            }
            for (String path : new TreeSet<>(comparator.getRemovedFilesName())) {
                list.add(new FileDiff(list.size(), path, null, false, "DELETED"));
            }
            this.files = List.copyOf(list);
        }

        public List<FileDiff> files() {
            return files;
        }

        /**
         * The same shape MonacoCore emits, for a file on one side only: the whole file as one inserted (or
         * deleted) range, the other editor empty. JSON is a valid JavaScript object literal.
         */
        private String oneSided(FileDiff file) {
            boolean added = file.status().equals("ADDED");
            String path = added ? file.right() : file.left();
            String content = (added ? project.getFileContentsAfter() : project.getFileContentsBefore()).getOrDefault(path, "");
            Map<String, Object> filled = Map.of("content", content, "ranges", content.isEmpty() ? List.of()
                    : List.of(Map.of("from", 0, "to", content.length(), "index", 0,
                    "kind", added ? "inserted" : "deleted", "tooltip", added ? "added file" : "deleted file")));
            Map<String, Object> empty = Map.of("content", "", "ranges", List.of());
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("moved", false);
            config.put("id", file.index());
            config.put("spv", false);
            config.put("file", path);
            config.put("left", added ? empty : filled);
            config.put("lcid", "left-container-" + file.index());
            config.put("right", added ? filled : empty);
            config.put("rcid", "right-container-" + file.index());
            config.put("mappings", List.of());
            // "</" would end the inline <script>; MonacoCore escapes it the same way.
            return json.writeValueAsString(config).replace("</", "<\\/");
        }

        public int refactoringCount() {
            return refactorings.size();
        }

        /** The file pair whose left or right path is {@code path}; else the first. */
        public int indexOf(String path) {
            if (path != null) {
                for (FileDiff f : files) {
                    if (path.equals(f.right()) || path.equals(f.left())) {
                        return f.index();
                    }
                }
            }
            return 0;
        }

        /** A JavaScript object literal for {@code mymonaco(...)} — RefactoringMiner's format, not JSON. */
        public String config(int index) {
            FileDiff file = files.get(index);
            if (!file.status().equals("MODIFIED")) {
                return oneSided(file);
            }
            var contents = comparator.getFileContentsPair(index);
            return new MonacoCore(comparator, comparator.getASTDiff(index), index, comparator.isMoveDiff(index),
                    contents.first, contents.second, refactorings, Map.of()).makeDiffConfig();
        }
    }

    private static final int CACHE_SIZE = 8;

    private final MigrationRiskService analysis;
    private final MigrationRiskProperties properties;
    private final JsonMapper json;
    /** Paging through files re-requests the same side; computing it again each time would redo the AST diff. */
    private final Map<String, DiffSet> cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, DiffSet> eldest) {
            return size() > CACHE_SIZE;
        }
    });

    public DiffViewService(MigrationRiskService analysis, MigrationRiskProperties properties, JsonMapper json) {
        this.analysis = analysis;
        this.properties = properties;
        this.json = json;
    }

    /**
     * Keyed by the inputs as given. Use commit ids (the analysis response has them) rather than branch names
     * for anything that should not change under the reader; folders are re-read only on a cache miss.
     */
    public DiffSet diff(Inputs in, Side side) {
        String key = in + "|" + side;
        DiffSet cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        DiffSet computed = analysis.withTrees(in, trees -> {
            Path workspace = properties.workDir().resolve("view-" + UUID.randomUUID());
            try {
                Exported exported = analysis.export(workspace, trees.baseline(), trees.head(), trees.target(),
                        trees.changes(), new ArrayList<>());
                Path after = side == Side.A ? exported.upstream() : exported.target();
                long started = System.nanoTime();
                ProjectASTDiff project = new GitHistoryRefactoringMinerImpl()
                        .diffAtDirectories(exported.before().toRealPath(), after.toRealPath());
                DiffSet set = new DiffSet(new DirComparator(project, diffs -> diffs), project, json);
                log.info("diff view {}: {} file pairs, {} refactorings in {} ms", side, set.files().size(),
                        set.refactoringCount(), (System.nanoTime() - started) / 1_000_000);
                return set;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                // ProjectASTDiff holds the file contents itself; the exported copies are not needed any more.
                FileSystemUtils.deleteRecursively(workspace.toFile());
            }
        });
        cache.put(key, computed);
        return computed;
    }
}
