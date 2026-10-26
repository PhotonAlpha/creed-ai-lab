package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.config.MigrationRiskProperties;
import com.creed.resource.migrationrisk.git.ChangedFile;
import com.creed.resource.migrationrisk.service.MigrationRiskService.Exported;
import com.creed.resource.migrationrisk.service.MigrationRiskService.Inputs;
import com.creed.resource.migrationrisk.source.SourceTree;
import gui.webdiff.dir.DirComparator;
import gui.webdiff.viewers.monaco.MonacoCore;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.refactoringminer.api.Refactoring;
import org.refactoringminer.astDiff.models.ASTDiff;
import org.refactoringminer.astDiff.models.ProjectASTDiff;
import org.refactoringminer.rm1.GitHistoryRefactoringMinerImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * RefactoringMiner's AST diff for one side of an analysis, in the shape its own Monaco viewer renders:
 * side {@code A} = repo1 baseline -> repo1 head (what to sync), side {@code B} = repo1 head -> repo2 head (what the
 * sync has to bridge: the upstream code next to where the migration put it). Same file subset as the analysis.
 *
 * <p>Side B is <em>not</em> the analysis's second RefactoringMiner run (baseline -> repo2): that one shares its
 * left side with run A so the two can be cross-checked, which is right for deciding a level but wrong for reading —
 * a reviewer porting a change wants repo1's current code on the left, not the baseline's.
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
     * {@code text}: a non-Java file (config, template ...) shown as a plain line diff — RefactoringMiner reads Java only.
     */
    public record FileDiff(int index, String left, String right, boolean moved, String status, boolean text) {
    }

    /** A non-Java file pair, read while the trees were open; null text = that side has no such file. */
    record TextPair(String left, String right, String leftText, String rightText) {
    }

    /** All file pairs of one side; {@link #config} renders one of them. */
    public static final class DiffSet {
        private final DirComparator comparator;
        private final ProjectASTDiff project;
        private final List<Refactoring> refactorings;
        private final List<FileDiff> files;
        private final Map<Integer, TextPair> texts = new LinkedHashMap<>();
        private final JsonMapper json;

        DiffSet(DirComparator comparator, ProjectASTDiff project, List<TextPair> textPairs, JsonMapper json) {
            this.comparator = comparator;
            this.project = project;
            this.refactorings = project.getRefactorings();
            this.json = json;
            List<FileDiff> list = new ArrayList<>();
            for (int i = 0; i < comparator.getNumOfDiffs(); i++) {
                ASTDiff d = comparator.getASTDiff(i);
                list.add(new FileDiff(i, d.getSrcPath(), d.getDstPath(), comparator.isMoveDiff(i), "MODIFIED", false));
            }
            // DirComparator only lists mapped pairs; a file that exists on one side only would be invisible, and
            // on side A every file of a new upstream module is exactly that.
            for (String path : new TreeSet<>(comparator.getAddedFilesName())) {
                list.add(new FileDiff(list.size(), null, path, false, "ADDED", false));
            }
            for (String path : new TreeSet<>(comparator.getRemovedFilesName())) {
                list.add(new FileDiff(list.size(), path, null, false, "DELETED", false));
            }
            for (TextPair t : textPairs) {
                String status = t.leftText() == null ? "ADDED" : t.rightText() == null ? "DELETED" : "MODIFIED";
                texts.put(list.size(), t);
                list.add(new FileDiff(list.size(), t.leftText() == null ? null : t.left(),
                        t.rightText() == null ? null : t.right(), false, status, true));
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

        /**
         * MonacoCore's config shape for a non-Java pair: histogram line diff, removed lines marked {@code deleted}
         * on the left, added lines {@code inserted} on the right. No mappings — there is no AST to link.
         */
        static String textConfig(FileDiff file, TextPair pair, JsonMapper json) {
            String left = pair.leftText() == null ? "" : pair.leftText();
            String right = pair.rightText() == null ? "" : pair.rightText();
            RawText a = new RawText(left.getBytes(StandardCharsets.UTF_8));
            RawText b = new RawText(right.getBytes(StandardCharsets.UTF_8));
            int[] aStarts = lineStarts(left);
            int[] bStarts = lineStarts(right);
            List<Map<String, Object>> leftRanges = new ArrayList<>();
            List<Map<String, Object>> rightRanges = new ArrayList<>();
            int n = 0;
            for (Edit e : new HistogramDiff().diff(RawTextComparator.DEFAULT, a, b)) {
                if (e.getEndA() > e.getBeginA()) {
                    leftRanges.add(range(aStarts, e.getBeginA(), e.getEndA(), n, "deleted", left.length()));
                }
                if (e.getEndB() > e.getBeginB()) {
                    rightRanges.add(range(bStarts, e.getBeginB(), e.getEndB(), n, "inserted", right.length()));
                }
                n++;
            }
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("moved", false);
            config.put("id", file.index());
            config.put("spv", false);
            config.put("file", file.right() != null ? file.right() : file.left());
            config.put("left", Map.of("content", left, "ranges", leftRanges));
            config.put("lcid", "left-container-" + file.index());
            config.put("right", Map.of("content", right, "ranges", rightRanges));
            config.put("rcid", "right-container-" + file.index());
            config.put("mappings", List.of());
            return json.writeValueAsString(config).replace("</", "<\\/");
        }

        /** Character offset of each line start (plus one past the end), in Java chars like Monaco counts. */
        private static int[] lineStarts(String text) {
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    starts.add(i + 1);
                }
            }
            starts.add(text.length());
            return starts.stream().mapToInt(Integer::intValue).toArray();
        }

        private static Map<String, Object> range(int[] starts, int fromLine, int toLine, int index, String kind, int length) {
            int from = starts[Math.min(fromLine, starts.length - 1)];
            int to = Math.min(starts[Math.min(toLine, starts.length - 1)], length);
            return Map.of("from", from, "to", Math.max(from, to), "index", index, "kind", kind, "tooltip", kind + " lines");
        }

        public int refactoringCount() {
            return refactorings.size();
        }

        /** The file pair whose left or right path is {@code path}; the first without a path; -1 when none matches. */
        public int indexOf(String path) {
            if (path == null) {
                return 0;
            }
            for (FileDiff f : files) {
                if (path.equals(f.right()) || path.equals(f.left())) {
                    return f.index();
                }
            }
            return -1;
        }

        /** A JavaScript object literal for {@code mymonaco(...)} — RefactoringMiner's format, not JSON. */
        public String config(int index) {
            FileDiff file = files.get(index);
            if (file.text()) {
                return textConfig(file, texts.get(index), json);
            }
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

    /**
     * The non-Java files of the change set: A = baseline -> HEAD of each; B = HEAD -> repo2's counterpart — the same
     * path, else the converted one ({@code x.vm} -> {@code x.ftl}, by {@code extension-mappings}) at the same path or
     * the nearest same-named file. Binary files have no text on either side and are left out.
     */
    private List<TextPair> textPairs(MigrationRiskService.Trees trees, Side side) {
        List<TextPair> pairs = new ArrayList<>();
        for (ChangedFile f : trees.changes()) {
            if (f.isJava()) {
                continue;
            }
            if (side == Side.A) {
                String before = f.oldPath() == null ? null : trees.baseline().read(f.oldPath()).orElse(null);
                String after = f.newPath() == null ? null : trees.head().read(f.newPath()).orElse(null);
                if (before != null || after != null) {
                    pairs.add(new TextPair(f.oldPath() != null ? f.oldPath() : f.newPath(),
                            f.newPath() != null ? f.newPath() : f.oldPath(), before, after));
                }
                continue;
            }
            if (f.newPath() == null) {
                continue; // deleted upstream: no HEAD version to put next to repo2's
            }
            String head = trees.head().read(f.newPath()).orElse(null);
            if (head == null) {
                continue;
            }
            String counterpart = counterpart(trees.target(), f.newPath());
            pairs.add(new TextPair(f.newPath(), counterpart != null ? counterpart : f.newPath(), head,
                    counterpart == null ? null : trees.target().read(counterpart).orElse(null)));
        }
        return pairs;
    }

    private String counterpart(SourceTree target, String path) {
        if (target.contentId(path).isPresent()) {
            return path;
        }
        int dot = path.lastIndexOf('.');
        String to = dot < 0 || dot < path.lastIndexOf('/') ? null
                : properties.extensionMappings().get(path.substring(dot + 1).toLowerCase(Locale.ROOT));
        if (to == null) {
            return null;
        }
        String converted = path.substring(0, dot + 1) + to;
        if (target.contentId(converted).isPresent()) {
            return converted;
        }
        List<String> named = target.pathsByFileName().getOrDefault(converted.substring(converted.lastIndexOf('/') + 1), List.of());
        return named.isEmpty() ? null : named.getFirst();
    }

    /** A file pair rendered on its own, outside the cached set. */
    public record TextView(FileDiff file, String config) {
    }

    /**
     * Side B of a non-Java file against an explicit repo2 file — a config key repo2 keeps in
     * {@code config/application.yml} of repo2B, say, where the same-path lookup finds nothing. Two file reads, no
     * RefactoringMiner, so it is not cached.
     */
    public TextView text(Inputs in, String headPath, String targetPath, int index) {
        return analysis.withTrees(in, trees -> {
            TextPair pair = new TextPair(headPath, targetPath, trees.head().read(headPath).orElse(null),
                    trees.target().read(targetPath).orElse(null));
            String status = pair.leftText() == null ? "ADDED" : pair.rightText() == null ? "DELETED" : "MODIFIED";
            FileDiff file = new FileDiff(index, pair.leftText() == null ? null : headPath,
                    pair.rightText() == null ? null : targetPath, false, status, true);
            return new TextView(file, DiffSet.textConfig(file, pair, json));
        });
    }

    /**
     * The three versions of one file side by side — repo1 baseline, repo1 HEAD, repo2 — with whole-line marks:
     * lines repo1 changed since the baseline ({@code upstream}), lines the migration changed ({@code migration}),
     * and on the baseline, lines both touched ({@code both}): where a sync needs a real merge.
     *
     * @param file   repo1 HEAD's path (or the baseline's, for a file deleted upstream)
     * @param target repo2's file; null = the same path, else its converted name ({@code extension-mappings})
     */
    public ThreeWay threeWay(Inputs in, String file, String target) {
        return analysis.withTrees(in, trees -> {
            ChangedFile change = trees.changes().stream()
                    .filter(f -> file.equals(f.newPath()) || file.equals(f.oldPath())).findFirst().orElse(null);
            String basePath = change == null ? file : change.oldPath();
            String headPath = change == null ? file : change.newPath();
            String targetPath = target != null && !target.isBlank() ? target
                    : counterpart(trees.target(), headPath != null ? headPath : file);
            String base = basePath == null ? null : trees.baseline().read(basePath).orElse(null);
            String head = headPath == null ? null : trees.head().read(headPath).orElse(null);
            String there = targetPath == null ? null : trees.target().read(targetPath).orElse(null);
            List<int[]> upstream = changedLines(base, head);
            List<int[]> migration = changedLines(base, there);
            List<Map<String, Object>> baseMarks = new ArrayList<>();
            int baseLines = base == null ? 0 : (int) base.lines().count();
            for (int line = 1; line <= baseLines; line++) {
                boolean up = touches(upstream, line);
                boolean mig = touches(migration, line);
                if (up || mig) {
                    baseMarks.add(Map.of("line", line, "kind", up && mig ? "both" : up ? "upstream" : "migration"));
                }
            }
            List<Map<String, Object>> panes = List.of(
                    pane("baseline", basePath, base, baseMarks),
                    pane("head", headPath, head, marks(upstream, "upstream")),
                    pane("repo2", targetPath, there, marks(migration, "migration")));
            String repo = trees.target() instanceof com.creed.resource.migrationrisk.source.MergedTree merged && targetPath != null
                    ? merged.origin(targetPath).orElse(null) : null;
            return new ThreeWay(basePath, headPath, targetPath, repo,
                    json.writeValueAsString(Map.of("panes", panes)).replace("</", "<\\/"));
        });
    }

    public record ThreeWay(String baselinePath, String headPath, String targetPath, String repo, String data) {
    }

    private static Map<String, Object> pane(String key, String path, String content, List<Map<String, Object>> marks) {
        Map<String, Object> pane = new LinkedHashMap<>();
        pane.put("key", key);
        pane.put("path", path);
        pane.put("missing", content == null);
        pane.put("content", content == null ? "" : content);
        pane.put("marks", marks);
        return pane;
    }

    /** Edits of {@code after} against {@code before} as [beginA, endA, beginB, endB], 0-based, end exclusive. */
    private static List<int[]> changedLines(String before, String after) {
        if (before == null || after == null) {
            return List.of();
        }
        List<int[]> edits = new ArrayList<>();
        for (Edit e : new HistogramDiff().diff(RawTextComparator.DEFAULT,
                new RawText(before.getBytes(StandardCharsets.UTF_8)), new RawText(after.getBytes(StandardCharsets.UTF_8)))) {
            edits.add(new int[]{e.getBeginA(), e.getEndA(), e.getBeginB(), e.getEndB()});
        }
        return edits;
    }

    /** {@code line} is 1-based; an insertion marks the baseline line it follows, so an added block still shows. */
    private static boolean touches(List<int[]> edits, int line) {
        for (int[] e : edits) {
            int begin = e[0];
            int end = e[1] == e[0] ? e[0] + 1 : e[1];
            if (line - 1 >= begin && line - 1 < end) {
                return true;
            }
        }
        return false;
    }

    private static List<Map<String, Object>> marks(List<int[]> edits, String kind) {
        List<Map<String, Object>> marks = new ArrayList<>();
        for (int[] e : edits) {
            for (int line = e[2]; line < e[3]; line++) {
                marks.add(Map.of("line", line + 1, "kind", kind));
            }
        }
        return marks;
    }

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
                Path left = side == Side.A ? exported.before() : exported.upstream();
                Path right = side == Side.A ? exported.upstream() : exported.target();
                long started = System.nanoTime();
                ProjectASTDiff project = new GitHistoryRefactoringMinerImpl()
                        .diffAtDirectories(left.toRealPath(), right.toRealPath());
                DiffSet set = new DiffSet(new DirComparator(project, diffs -> diffs), project,
                        textPairs(trees, side), json);
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
