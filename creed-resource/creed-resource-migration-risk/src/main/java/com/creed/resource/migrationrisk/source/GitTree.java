package com.creed.resource.migrationrisk.source;

import com.creed.resource.migrationrisk.git.GitRepositories;
import com.creed.resource.migrationrisk.git.GitRepositories.Snapshot;
import org.eclipse.jgit.lib.ObjectId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * A commit: files are read from git objects, never from the working tree. The file list and blob ids come from one
 * tree walk — comparing two whole repositories asks for every file's id, and a walk per path would be quadratic.
 */
public final class GitTree implements SourceTree {

    private final Snapshot snapshot;
    private Map<String, ObjectId> ids;
    private Map<String, List<String>> byName;

    public GitTree(Snapshot snapshot) {
        this.snapshot = snapshot;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    private Map<String, ObjectId> ids() {
        if (ids == null) {
            ids = GitRepositories.blobIds(snapshot.repository(), snapshot.commit());
        }
        return ids;
    }

    @Override
    public Optional<String> read(String path) {
        return path == null || !ids().containsKey(path) ? Optional.empty()
                : GitRepositories.read(snapshot.repository(), snapshot.commit(), path);
    }

    @Override
    public Optional<String> contentId(String path) {
        return Optional.ofNullable(path == null ? null : ids().get(path)).map(ObjectId::name);
    }

    @Override
    public Set<String> paths() {
        return Collections.unmodifiableSet(ids().keySet());
    }

    @Override
    public Map<String, List<String>> pathsByFileName() {
        if (byName == null) {
            Map<String, List<String>> map = new TreeMap<>();
            ids().keySet().forEach(p -> map.computeIfAbsent(p.substring(p.lastIndexOf('/') + 1), n -> new ArrayList<>()).add(p));
            byName = map;
        }
        return byName;
    }
}
