package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.config.MigrationRiskProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Repository paths arrive in the request body, and the process can read whatever its user can. Each path
 * is resolved to a real path (so {@code ..} and symlinks cannot step outside) and must lie under one of
 * {@code migration-risk.allowed-roots}, or the demo directory when the demo is on.
 */
@Component
public class RepositoryGuard {

    private final List<Path> roots = new ArrayList<>();

    public RepositoryGuard(MigrationRiskProperties properties) {
        properties.allowedRoots().forEach(root -> roots.add(realOrSelf(root)));
        if (properties.demo().enabled()) {
            // Created first: a not-yet-existing work dir resolves to /var/... on macOS while the demo
            // repositories, once created, resolve to /private/var/..., and startsWith would never match.
            try {
                Files.createDirectories(properties.workDir());
            } catch (IOException e) {
                throw new IllegalStateException("cannot create " + properties.workDir(), e);
            }
            roots.add(realOrSelf(properties.workDir()).resolve("demo"));
        }
    }

    /** {@code field} names the request field the path came from, so the UI can mark that input. */
    public Path check(String path, String field) {
        Path dir;
        try {
            dir = Path.of(path.trim()).toRealPath();
        } catch (IOException | RuntimeException e) {
            throw new PathNotAllowedException(field, "no such directory: " + path);
        }
        if (!Files.isDirectory(dir)) {
            throw new PathNotAllowedException(field, "not a directory: " + path);
        }
        if (roots.stream().noneMatch(dir::startsWith)) {
            throw new PathNotAllowedException(field, path + " is outside migration-risk.allowed-roots");
        }
        return dir;
    }

    public List<String> roots() {
        return roots.stream().map(Path::toString).toList();
    }

    private static Path realOrSelf(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    public static class PathNotAllowedException extends RuntimeException {
        private final String field;

        public PathNotAllowedException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }
}
