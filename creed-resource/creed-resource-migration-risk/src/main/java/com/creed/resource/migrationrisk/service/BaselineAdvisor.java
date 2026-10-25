package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.api.dto.AnalysisResponse.CommitView;
import com.creed.resource.migrationrisk.api.dto.RefsView;
import com.creed.resource.migrationrisk.git.GitRepositories;
import com.creed.resource.migrationrisk.git.GitRepositories.Snapshot;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;

/**
 * Helps pick the branches and the baseline: one repository's refs at a time (so a wrong repo2 path never
 * hides repo1's branches), and — when the two repositories share history, i.e. two clones of one origin —
 * the merge-base of the two selected heads, which is exactly "the repo1 commit the migration branched from".
 */
@Service
public class BaselineAdvisor {

    private static final int REF_LIMIT = 200;
    private static final int COMMIT_LIMIT = 30;

    private final RepositoryGuard guard;

    public BaselineAdvisor(RepositoryGuard guard) {
        this.guard = guard;
    }

    public RefsView refs(String path, String head) {
        try (Git git = GitRepositories.open(guard.check(path, "path"), "path")) {
            Snapshot resolved = GitRepositories.resolve(git, head, "head");
            List<CommitView> recent = GitRepositories.recentCommits(git.getRepository(), resolved.commit(), COMMIT_LIMIT)
                    .stream().map(c -> MigrationRiskService.commit(c.getName(), c)).toList();
            return new RefsView(currentBranch(git),
                    MigrationRiskService.commit(head, resolved.commit()),
                    GitRepositories.refNames(git.getRepository(), Constants.R_HEADS, REF_LIMIT),
                    GitRepositories.refNames(git.getRepository(), Constants.R_REMOTES, REF_LIMIT),
                    GitRepositories.refNames(git.getRepository(), Constants.R_TAGS, REF_LIMIT),
                    recent);
        }
    }

    public RefsView.Suggestion suggest(String repo1Path, String repo1Head, String repo2Path, String repo2Head) {
        try (Git repo1 = GitRepositories.open(guard.check(repo1Path, "repo1Path"), "repo1Path");
             Git repo2 = GitRepositories.open(guard.check(repo2Path, "repo2Path"), "repo2Path")) {
            Snapshot head1 = GitRepositories.resolve(repo1, repo1Head, "repo1Head");
            Snapshot head2 = GitRepositories.resolve(repo2, repo2Head, "repo2Head");
            Optional<ObjectId> base = GitRepositories.mergeBase(repo1.getRepository(), head1.commit(),
                    repo2.getRepository(), head2.commit());
            if (base.isEmpty()) {
                return new RefsView.Suggestion(null, "NO_SHARED_HISTORY");
            }
            // The merge-base is an ancestor of repo1's head, so repo1 always has it.
            Snapshot baseline = GitRepositories.resolve(repo1, base.get().name(), "baseline");
            return new RefsView.Suggestion(MigrationRiskService.commit(baseline.commit().abbreviate(10).name(),
                    baseline.commit()), "MERGE_BASE");
        }
    }

    /** Null on a detached HEAD, where JGit reports the commit id as the "branch". */
    private static String currentBranch(Git git) {
        try {
            String full = git.getRepository().getFullBranch();
            return full != null && full.startsWith(Constants.R_HEADS) ? Repository.shortenRefName(full) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
