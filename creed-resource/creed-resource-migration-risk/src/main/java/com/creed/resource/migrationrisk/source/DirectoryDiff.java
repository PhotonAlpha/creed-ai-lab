package com.creed.resource.migrationrisk.source;

import com.creed.resource.migrationrisk.git.ChangedFile;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

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
 * {@code git diff} for two folders: added / deleted / modified by content id, a deleted and an added file
 * with identical content paired as a rename, and the added line numbers from JGit's histogram diff — the
 * same algorithm {@code git diff} uses, so both modes flag the same lines.
 */
public final class DirectoryDiff {

    private DirectoryDiff() {
    }

    public static List<ChangedFile> diff(DirectoryTree from, DirectoryTree to) {
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

    private static List<Integer> addedLines(DirectoryTree from, String oldPath, DirectoryTree to, String newPath) {
        byte[] after = to.bytes(newPath).orElse(new byte[0]);
        if (RawText.isBinary(after)) {
            return List.of();
        }
        byte[] before = from == null ? new byte[0] : from.bytes(oldPath).orElse(new byte[0]);
        RawText a = new RawText(before);
        RawText b = new RawText(after);
        List<Integer> lines = new ArrayList<>();
        for (Edit edit : new HistogramDiff().diff(RawTextComparator.DEFAULT, a, b)) {
            IntStream.range(edit.getBeginB(), edit.getEndB()).forEach(line -> lines.add(line + 1));
        }
        return lines;
    }
}
