package com.creed.resource.migrationrisk.source;

import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectInserter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * A plain folder: whatever is on disk, uncommitted edits included, no git needed. Directories named in
 * {@code excludes} ({@code .git}, {@code target}, {@code node_modules} ...) are skipped at any depth — build
 * output differs between any two checkouts and would drown the real changes.
 */
public final class DirectoryTree implements SourceTree {

    private final Path root;
    private final Map<String, Path> files;
    private final Map<String, Optional<String>> ids = new HashMap<>();
    private Map<String, List<String>> byName;

    public DirectoryTree(Path root, Set<String> excludes) {
        this.root = root;
        this.files = Collections.unmodifiableMap(walk(root, excludes));
    }

    private static Map<String, Path> walk(Path root, Set<String> excludes) {
        Map<String, Path> files = new TreeMap<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(root) && excludes.contains(dir.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        // '/' on every OS: paths must match git's and RefactoringMiner's.
                        files.put(root.relativize(file).toString().replace('\\', '/'), file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    public Path root() {
        return root;
    }

    /** Every file path, sorted. */
    public Set<String> paths() {
        return files.keySet();
    }

    public Optional<byte[]> bytes(String path) {
        Path file = path == null ? null : files.get(path);
        if (file == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<String> read(String path) {
        return bytes(path).filter(b -> !RawText.isBinary(b)).map(b -> new String(b, StandardCharsets.UTF_8));
    }

    @Override
    public Optional<String> contentId(String path) {
        return ids.computeIfAbsent(String.valueOf(path), p -> bytes(path).map(b -> {
            try (ObjectInserter.Formatter f = new ObjectInserter.Formatter()) {
                return f.idFor(Constants.OBJ_BLOB, b).name();
            }
        }));
    }

    @Override
    public Map<String, List<String>> pathsByFileName() {
        if (byName == null) {
            Map<String, List<String>> map = new TreeMap<>();
            files.keySet().forEach(p -> map.computeIfAbsent(p.substring(p.lastIndexOf('/') + 1), n -> new ArrayList<>()).add(p));
            byName = map;
        }
        return byName;
    }
}
