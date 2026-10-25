package com.creed.resource.migrationrisk.source;

import com.creed.resource.migrationrisk.git.GitRepositories;
import com.creed.resource.migrationrisk.git.GitRepositories.Snapshot;
import org.eclipse.jgit.lib.ObjectId;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A commit: files are read from git objects, never from the working tree. */
public final class GitTree implements SourceTree {

    private final Snapshot snapshot;
    private Map<String, List<String>> byName;

    public GitTree(Snapshot snapshot) {
        this.snapshot = snapshot;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    @Override
    public Optional<String> read(String path) {
        return GitRepositories.read(snapshot.repository(), snapshot.commit(), path);
    }

    @Override
    public Optional<String> contentId(String path) {
        return GitRepositories.blobId(snapshot.repository(), snapshot.commit(), path).map(ObjectId::name);
    }

    @Override
    public Map<String, List<String>> pathsByFileName() {
        if (byName == null) {
            byName = GitRepositories.pathsByFileName(snapshot.repository(), snapshot.commit());
        }
        return byName;
    }
}
