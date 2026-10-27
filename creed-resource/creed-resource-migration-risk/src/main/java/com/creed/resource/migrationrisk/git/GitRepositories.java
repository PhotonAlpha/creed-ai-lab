package com.creed.resource.migrationrisk.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Read-only JGit access: resolve a revision, list a commit's files, read blobs. Nothing here
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

    /** Every file path at that commit with its blob id, in one tree walk (sorted by path). */
    public static Map<String, ObjectId> blobIds(Repository repository, RevCommit commit) {
        Map<String, ObjectId> ids = new TreeMap<>();
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(commit.getTree());
            walk.setRecursive(true);
            while (walk.next()) {
                ids.put(walk.getPathString(), walk.getObjectId(0));
            }
            return ids;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
