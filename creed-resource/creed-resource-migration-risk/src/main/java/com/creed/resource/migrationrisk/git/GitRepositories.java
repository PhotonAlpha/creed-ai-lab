package com.creed.resource.migrationrisk.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.IntStream;

/**
 * Read-only JGit access: resolve a revision, diff two commits, read or export blobs. Nothing here
 * checks out a working tree or touches the repository's own files — the comparison trees are written
 * to a scratch directory, so the user's checkout (and whatever is uncommitted in it) is never disturbed.
 */
public final class GitRepositories {

    private GitRepositories() {
    }

    /** One opened repository and a resolved commit in it. Close the {@link Git} when done. */
    public record Snapshot(Git git, RevCommit commit) {

        public Repository repository() {
            return git.getRepository();
        }

        public String id() {
            return commit.getName();
        }
    }

    /** {@code field} names the request field the path came from, so the UI can mark that input. */
    public static Git open(Path dir, String field) {
        try {
            return Git.open(dir.toFile());
        } catch (IOException e) {
            throw new InvalidRepositoryException(field, dir + " is not a git repository");
        }
    }

    public static Snapshot resolve(Git git, String revision, String field) {
        try {
            ObjectId id = git.getRepository().resolve(revision + "^{commit}");
            if (id == null) {
                throw new InvalidRepositoryException(field, "unknown revision '" + revision + "' in "
                        + git.getRepository().getDirectory().getParent());
            }
            try (RevWalk walk = new RevWalk(git.getRepository())) {
                return new Snapshot(git, walk.parseCommit(id));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (org.eclipse.jgit.errors.RevisionSyntaxException e) {
            throw new InvalidRepositoryException(field, "invalid revision '" + revision + "': " + e.getMessage());
        }
    }

    /**
     * The newest common ancestor of {@code a} (in {@code repoA}) and {@code b} (in {@code repoB}), when one
     * object database holds both commits — two clones of the same origin usually do, since a fetch brings in
     * the other branches. Empty when the histories are unrelated (repo2 started from a copy, not a clone).
     */
    public static Optional<ObjectId> mergeBase(Repository repoA, ObjectId a, Repository repoB, ObjectId b) {
        try {
            if (repoB.getObjectDatabase().has(a)) {
                return mergeBase(repoB, a, b);
            }
            if (repoA.getObjectDatabase().has(b)) {
                return mergeBase(repoA, a, b);
            }
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Optional<ObjectId> mergeBase(Repository repository, ObjectId a, ObjectId b) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(a));
            walk.markStart(walk.parseCommit(b));
            RevCommit base = walk.next();
            return base == null ? Optional.empty() : Optional.of(base.copy());
        }
    }

    /** Short names of local branches, remote-tracking branches and tags (newest refs are not sorted first). */
    public static List<String> refNames(Repository repository, String prefix, int limit) {
        try {
            return repository.getRefDatabase().getRefsByPrefix(prefix).stream()
                    .map(r -> Repository.shortenRefName(r.getName()))
                    .filter(name -> !name.endsWith("/HEAD"))
                    .sorted()
                    .limit(limit)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The newest {@code limit} commits reachable from {@code head}. */
    public static List<RevCommit> recentCommits(Repository repository, RevCommit head, int limit) {
        try (RevWalk walk = new RevWalk(repository)) {
            walk.markStart(walk.parseCommit(head));
            List<RevCommit> commits = new ArrayList<>();
            for (RevCommit c : walk) {
                if (commits.size() >= limit) {
                    break;
                }
                commits.add(c);
            }
            return commits;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** True when {@code ancestor} is reachable from {@code head} (the baseline really is in repo1's history). */
    public static boolean isAncestor(Repository repository, RevCommit ancestor, RevCommit head) {
        try (RevWalk walk = new RevWalk(repository)) {
            return walk.isMergedInto(walk.parseCommit(ancestor), walk.parseCommit(head));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@code git diff --find-renames from..to}, with the lines each file adds. Whitespace-only edits still
     * count — a re-indented block is a change someone has to sync.
     */
    public static List<ChangedFile> diff(Repository repository, RevCommit from, RevCommit to) {
        try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            formatter.setRepository(repository);
            formatter.setDiffComparator(RawTextComparator.DEFAULT);
            formatter.setDetectRenames(true);
            List<ChangedFile> files = new ArrayList<>();
            for (DiffEntry entry : formatter.scan(from.getTree(), to.getTree())) {
                files.add(changedFile(formatter, entry));
            }
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ChangedFile changedFile(DiffFormatter formatter, DiffEntry entry) throws IOException {
        ChangedFile.Change change = switch (entry.getChangeType()) {
            case ADD, COPY -> ChangedFile.Change.ADDED;
            case DELETE -> ChangedFile.Change.DELETED;
            case MODIFY -> ChangedFile.Change.MODIFIED;
            case RENAME -> ChangedFile.Change.RENAMED;
        };
        String oldPath = change == ChangedFile.Change.ADDED ? null : entry.getOldPath();
        String newPath = change == ChangedFile.Change.DELETED ? null : entry.getNewPath();
        List<Integer> added = new ArrayList<>();
        if (newPath != null) {
            for (Edit edit : formatter.toFileHeader(entry).toEditList()) {
                // Edit lines are 0-based, end-exclusive.
                IntStream.range(edit.getBeginB(), edit.getEndB()).forEach(line -> added.add(line + 1));
            }
        }
        return new ChangedFile(change, oldPath, newPath, added);
    }

    /** The file's text at that commit; empty when the path does not exist there or is binary. */
    public static Optional<String> read(Repository repository, RevCommit commit, String path) {
        try (TreeWalk walk = TreeWalk.forPath(repository, path, commit.getTree())) {
            if (walk == null) {
                return Optional.empty();
            }
            ObjectLoader loader = repository.open(walk.getObjectId(0));
            byte[] bytes = loader.getBytes();
            return RawText.isBinary(bytes) ? Optional.empty() : Optional.of(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The blob id at that path — equal ids mean byte-identical content, text or binary. */
    public static Optional<ObjectId> blobId(Repository repository, RevCommit commit, String path) {
        try (TreeWalk walk = TreeWalk.forPath(repository, path, commit.getTree())) {
            return walk == null ? Optional.empty() : Optional.of(walk.getObjectId(0));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every file path at that commit, keyed by file name ({@code OrderService.java} -> its paths). */
    public static Map<String, List<String>> pathsByFileName(Repository repository, RevCommit commit) {
        Map<String, List<String>> byName = new TreeMap<>();
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(commit.getTree());
            walk.setRecursive(true);
            while (walk.next()) {
                byName.computeIfAbsent(walk.getNameString(), name -> new ArrayList<>()).add(walk.getPathString());
            }
            return byName;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes each existing path's blob under {@code target}, keeping the repository-relative layout. */
    public static void export(Repository repository, RevCommit commit, Iterable<String> paths, Path target) {
        for (String path : paths) {
            read(repository, commit, path).ifPresent(text -> {
                try {
                    Path file = target.resolve(path);
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, text);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    public static class InvalidRepositoryException extends RuntimeException {
        private final String field;

        public InvalidRepositoryException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }
}
