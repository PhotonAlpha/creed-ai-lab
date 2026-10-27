package com.creed.resource.migrationrisk.source;

import com.creed.resource.migrationrisk.git.ChangedFile;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * {@code git diff} for any two trees — two commits of unrelated repositories, two folders, or a commit and a merged
 * repo2: added / deleted / modified by content id (git blob ids in every kind of tree), a deleted and an added file
 * with identical content paired as a rename, and the added line numbers from JGit's histogram diff — the algorithm
 * {@code git diff} uses.
 *
 * <p>The analysis calls it as {@code diff(repo2, repo1)}: ADDED = only repo1 has the file, DELETED = only repo2,
 * MODIFIED = both, different, and the "added" lines are the lines of repo1's file that repo2's lacks.
 */
public final class TreeDiff {

    private TreeDiff() {
    }

    public static List<ChangedFile> diff(SourceTree from, SourceTree to) {
        Set<String> all = new LinkedHashSet<>(from.paths());
        all.addAll(to.paths());
        List<String> deleted = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<ChangedFile> files = new ArrayList<>();
        for (String path : all) {
            boolean before = from.paths().contains(path);
            boolean after = to.paths().contains(path);
            if (before && !after) {
                deleted.add(path);
            } else if (!before) {
                added.add(path);
            } else if (!Objects.equals(from.contentId(path), to.contentId(path))) {
                files.add(new ChangedFile(ChangedFile.Change.MODIFIED, path, path, addedLines(from, path, to, path)));
            }
        }
        // Exact renames only: git would also pair similar files, but "similar" needs a threshold nobody asked for.
        // A queue per id: several deleted files can share one content (empty files, copies), and each may
        // pair with one added file at most.
        Map<String, Deque<String>> deletedById = new HashMap<>();
        deleted.forEach(p -> deletedById.computeIfAbsent(from.contentId(p).orElse(""), id -> new ArrayDeque<>()).add(p));
        Set<String> renamed = new HashSet<>();
        for (String path : added) {
            Deque<String> candidates = deletedById.get(to.contentId(path).orElse(""));
            String renamedFrom = candidates == null ? null : candidates.poll();
            if (renamedFrom != null) {
                renamed.add(renamedFrom);
                files.add(new ChangedFile(ChangedFile.Change.RENAMED, renamedFrom, path, List.of()));
            } else {
                files.add(new ChangedFile(ChangedFile.Change.ADDED, null, path, addedLines(null, null, to, path)));
            }
        }
        deleted.stream().filter(p -> !renamed.contains(p))
                .forEach(p -> files.add(new ChangedFile(ChangedFile.Change.DELETED, p, null, List.of())));
        files.sort((a, b) -> a.path().compareTo(b.path()));
        return files;
    }

    /** Binary files read as absent (no text), so they get no line numbers. */
    private static List<Integer> addedLines(SourceTree from, String oldPath, SourceTree to, String newPath) {
        String after = to.read(newPath).orElse(null);
        if (after == null) {
            return List.of();
        }
        String before = from == null ? "" : from.read(oldPath).orElse("");
        RawText a = new RawText(before.getBytes(StandardCharsets.UTF_8));
        RawText b = new RawText(after.getBytes(StandardCharsets.UTF_8));
        List<Integer> lines = new ArrayList<>();
        for (Edit edit : new HistogramDiff().diff(RawTextComparator.DEFAULT, a, b)) {
            IntStream.range(edit.getBeginB(), edit.getEndB()).forEach(line -> lines.add(line + 1));
        }
        return lines;
    }
}
