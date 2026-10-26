package com.creed.resource.migrationrisk.source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * One version of a code base, read by repository-relative path: a git commit ({@link GitTree}) or a plain
 * folder ({@link DirectoryTree}). The analysis only ever sees this, so it does not care which one it got.
 */
public interface SourceTree {

    /** The file's text; empty when the path does not exist here or is binary. */
    Optional<String> read(String path);

    /**
     * A content id — git's blob id, computed the same way for folders — so equal ids mean byte-identical
     * content in either kind of tree. Empty when the path does not exist.
     */
    Optional<String> contentId(String path);

    /** Every file path, keyed by file name ({@code OrderService.java} -> its paths). */
    Map<String, List<String>> pathsByFileName();

    /** Every file path, sorted. */
    default Set<String> paths() {
        Set<String> paths = new TreeSet<>();
        pathsByFileName().values().forEach(paths::addAll);
        return paths;
    }

    /** Writes each existing path's text under {@code target}, keeping the relative layout. */
    default void export(Iterable<String> paths, Path target) {
        for (String path : paths) {
            read(path).ifPresent(text -> {
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
}
