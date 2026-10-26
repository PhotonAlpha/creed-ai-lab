package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.config.MigrationRiskProperties;
import com.creed.resource.migrationrisk.config.MigrationRiskProperties.Credential;
import com.creed.resource.migrationrisk.config.MigrationRiskProperties.Remotes;
import com.creed.resource.migrationrisk.git.GitRepositories.InvalidRepositoryException;
import com.creed.resource.migrationrisk.service.RepositoryGuard.PathNotAllowedException;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Git URLs as repository inputs. Each URL gets one bare mirror under {@code <work-dir>/remotes/<sha1(url)>}:
 * cloned on first use, fetched (branches and tags, pruned) when the last fetch is older than
 * {@code remotes.fetch-interval} — the refs picker asks while the user types, and every request must not hit
 * the server. A bare clone maps the remote's branches straight to {@code refs/heads/*}, so branch names resolve
 * exactly as in a local clone.
 *
 * <p>Only {@code https://} / {@code http://} (and {@code file://} when allowed): JGit's ssh needs an extra
 * transport and key handling this service does not want. Credentials come from configuration per host, never
 * from the request — a URL with a password in it is refused.
 */
@Component
@Slf4j
public class RemoteRepositories {

    private static final Pattern URL = Pattern.compile("^(https?|file|ssh|git)://.*|^[\\w.-]+@[\\w.-]+:.*");

    private final Remotes config;
    private final Path root;
    private final Clock clock;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, Instant> fetched = new ConcurrentHashMap<>();

    public RemoteRepositories(MigrationRiskProperties properties, Clock clock) {
        this.config = properties.remotes();
        this.root = properties.workDir().resolve("remotes");
        this.clock = clock;
    }

    public static boolean isUrl(String input) {
        return input != null && URL.matcher(input.trim()).matches();
    }

    /** The local bare mirror of {@code url}, cloned or fetched as needed. */
    public Path local(String url, String field) {
        String u = url.trim();
        URI uri = check(u, field);
        Path dir = root.resolve(sha1(u));
        synchronized (locks.computeIfAbsent(u, k -> new Object())) {
            try {
                if (!Files.isDirectory(dir.resolve("objects"))) {
                    FileSystemUtils.deleteRecursively(dir);
                    Files.createDirectories(dir.getParent());
                    log.info("cloning {} into {}", u, dir);
                    try (Git ignored = Git.cloneRepository().setURI(u).setBare(true).setDirectory(dir.toFile())
                            .setCloneAllBranches(true).setCredentialsProvider(credentials(uri)).call()) {
                        fetched.put(u, Instant.now(clock));
                    }
                } else if (fetched.getOrDefault(u, Instant.EPOCH).plus(config.fetchInterval()).isBefore(Instant.now(clock))) {
                    try (Git git = Git.open(dir.toFile())) {
                        git.fetch().setRemote("origin").setRemoveDeletedRefs(true).setTagOpt(TagOpt.FETCH_TAGS)
                                .setCredentialsProvider(credentials(uri)).call();
                    }
                    fetched.put(u, Instant.now(clock));
                    log.info("fetched {}", u);
                }
            } catch (GitAPIException e) {
                throw new InvalidRepositoryException(field, "cannot clone / fetch " + u + ": " + e.getMessage());
            } catch (java.io.IOException e) {
                throw new InvalidRepositoryException(field, "cannot prepare a mirror of " + u + ": " + e.getMessage());
            }
        }
        return dir;
    }

    private URI check(String url, String field) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new InvalidRepositoryException(field, "not a git URL: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("file")) {
            if (!config.allowFileUrls()) {
                throw new PathNotAllowedException(field, "file:// URLs are off (migration-risk.remotes.allow-file-urls)");
            }
            return uri;
        }
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new InvalidRepositoryException(field, "only https:// git URLs are supported, not " + url);
        }
        if (uri.getUserInfo() != null && uri.getUserInfo().contains(":")) {
            throw new InvalidRepositoryException(field, "put credentials in migration-risk.remotes.credentials, not in the URL");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (config.allowedHosts().stream().noneMatch(h -> h.equalsIgnoreCase(host))) {
            throw new PathNotAllowedException(field, host + " is not in migration-risk.remotes.allowed-hosts");
        }
        return uri;
    }

    private CredentialsProvider credentials(URI uri) {
        String host = uri.getHost();
        return config.credentials().stream().filter(c -> host != null && c.host().equalsIgnoreCase(host)).findFirst()
                .filter(c -> c.password() != null && !c.password().isBlank())
                .map(RemoteRepositories::provider).orElse(null);
    }

    private static CredentialsProvider provider(Credential c) {
        // GitHub / GitLab / Bitbucket accept any username with a token as the password
        return new UsernamePasswordCredentialsProvider(c.username() == null || c.username().isBlank() ? "token" : c.username(),
                c.password());
    }

    private static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
