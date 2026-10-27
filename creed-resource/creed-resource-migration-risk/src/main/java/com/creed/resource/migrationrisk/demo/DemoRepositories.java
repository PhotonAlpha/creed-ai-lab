package com.creed.resource.migrationrisk.demo;

import com.creed.resource.migrationrisk.api.dto.MigrationRiskSettings;
import com.creed.resource.migrationrisk.config.MigrationRiskProperties;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

/**
 * Generated repositories that exercise every risk category, so the page and the tests have something to analyse
 * without real checkouts:
 * <ul>
 *   <li>{@code repo1}: {@code demo/repo1} — the other team's code base, still on Boot 3 / Jackson 2;</li>
 *   <li>{@code repo2}: {@code demo/repo2}, an older state of it migrated to Boot 4 / Jackson 3 (no shared history);</li>
 *   <li>{@code repo2b}: {@code demo/repo2b}, what the migration moved into a second repository — the FreeMarker
 *       templates (converted from Velocity), the tests and a {@code config/application.yml}. Analysed with repo2
 *       only, those are what repo2 "lacks"; with repo2B added, they are found.</li>
 * </ul>
 * The same versions are also written as plain folders under {@code demo/folders/} for folder mode.
 * Rebuilt at every start under {@code <work-dir>/demo}. Fixed authors and commit times make the commit ids
 * identical on every run.
 */
@Component
@ConditionalOnProperty(value = "migration-risk.demo.enabled", matchIfMissing = true)
@Slf4j
public class DemoRepositories {

    private final Path repo1;
    private final Path repo2;
    private final Path repo2b;
    private final Path folders;

    public DemoRepositories(MigrationRiskProperties properties) {
        Path root = properties.workDir().resolve("demo");
        this.repo1 = root.resolve("repo1");
        this.repo2 = root.resolve("repo2");
        this.repo2b = root.resolve("repo2b");
        this.folders = root.resolve("folders");
        try {
            FileSystemUtils.deleteRecursively(root);
            try (Git git = Git.init().setDirectory(repo1.toFile()).setInitialBranch("main").call()) {
                commit(git, "demo/repo1", "Order service: cancel reason, retry config, sales report", "2026-03-02T15:30:00Z");
            }
            try (Git git = Git.init().setDirectory(repo2.toFile()).setInitialBranch("main").call()) {
                commit(git, "demo/repo2", "Migrate to Spring Boot 4 / Jackson 3", "2026-02-10T11:00:00Z");
            }
            try (Git git = Git.init().setDirectory(repo2b.toFile()).setInitialBranch("main").call()) {
                commit(git, "demo/repo2b", "Templates (FreeMarker), tests and shared config", "2026-02-12T10:00:00Z");
            }
            copyFolder("demo/repo1", folders.resolve("repo1"));
            copyFolder("demo/repo2", folders.resolve("repo2"));
            copyFolder("demo/repo2b", folders.resolve("repo2b"));
            log.info("demo repositories ready under {}", root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GitAPIException e) {
            throw new IllegalStateException("cannot build the demo repositories", e);
        }
    }

    /** Replaces the working tree with the classpath folder and commits the result, deletions included. */
    private void commit(Git git, String folder, String message, String time) throws IOException, GitAPIException {
        Path dir = git.getRepository().getWorkTree().toPath();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(p -> !p.getFileName().toString().equals(".git")).toList()) {
                FileSystemUtils.deleteRecursively(p);
            }
        }
        copyFolder(folder, dir);
        git.add().addFilepattern(".").call();
        git.add().addFilepattern(".").setUpdate(true).call();
        PersonIdent ident = new PersonIdent("Creed Demo", "demo@creed.local", Instant.parse(time), ZoneOffset.UTC);
        git.commit().setMessage(message).setAuthor(ident).setCommitter(ident).setSign(false).call();
    }

    /** Copies a classpath folder (from target/classes or the jar alike) into {@code dir}. */
    private static void copyFolder(String folder, Path dir) throws IOException {
        String prefix = folder + "/";
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:" + prefix + "**")) {
            String url = resource.getURL().toString();
            if (!resource.isReadable() || url.endsWith("/")) {
                continue;
            }
            Path target = dir.resolve(url.substring(url.lastIndexOf(prefix) + prefix.length()));
            Files.createDirectories(target.getParent());
            try (InputStream in = resource.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public MigrationRiskSettings.Demo view() {
        return new MigrationRiskSettings.Demo(repo1.toString(), "HEAD", repo2.toString(), "HEAD", repo2b.toString(),
                folders.resolve("repo1").toString(), folders.resolve("repo2").toString(), folders.resolve("repo2b").toString());
    }
}
