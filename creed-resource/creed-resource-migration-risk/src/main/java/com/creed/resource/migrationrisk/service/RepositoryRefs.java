package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.api.dto.AnalysisResponse.CommitView;
import com.creed.resource.migrationrisk.api.dto.RefsView;
import com.creed.resource.migrationrisk.git.GitRepositories;
import com.creed.resource.migrationrisk.git.GitRepositories.Snapshot;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/** Helps pick the branches: one repository's refs at a time, so a wrong repo2 path never hides repo1's branches. */
@Service
public class RepositoryRefs {

    private static final int REF_LIMIT = 200;
    private static final int COMMIT_LIMIT = 30;

    private final RepositoryLocator locator;

    public RepositoryRefs(RepositoryLocator locator) {
        this.locator = locator;
    }

    public RefsView refs(String path, String head) {
        try (Git git = GitRepositories.open(locator.repository(path, "path"), "path")) {
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
