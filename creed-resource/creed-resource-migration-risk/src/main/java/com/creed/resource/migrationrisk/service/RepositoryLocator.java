package com.creed.resource.migrationrisk.service;

import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Where a git-mode repository input lives on this host: a path is checked against the allowed roots, a URL is
 * cloned / fetched by {@link RemoteRepositories}. Folder mode never comes here — a URL is not a folder.
 */
@Component
public class RepositoryLocator {

    private final RepositoryGuard guard;
    private final RemoteRepositories remotes;

    public RepositoryLocator(RepositoryGuard guard, RemoteRepositories remotes) {
        this.guard = guard;
        this.remotes = remotes;
    }

    public Path repository(String pathOrUrl, String field) {
        return RemoteRepositories.isUrl(pathOrUrl) ? remotes.local(pathOrUrl, field) : guard.check(pathOrUrl, field);
    }

    /** How the analysis names it: the URL as given, or the resolved path. */
    public String display(String input, Path resolved) {
        return RemoteRepositories.isUrl(input) ? input.trim() : resolved.toString();
    }
}
