package com.creed.resource.migrationrisk.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * @param allowedRoots         repositories are opened only under one of these (resolved to real paths)
 * @param workDir              scratch space for the exported comparison trees
 * @param maxChangedFiles      more differing files than this between repo1 and repo2 are refused
 * @param maxCandidatesPerName how many same-named repo2 files are pulled in to catch a moved class
 * @param directoryExcludes    folder mode: directory names skipped at any depth (VCS data, build output, IDE files)
 * @param configFiles          regex over repo paths: besides Spring's application* / bootstrap* files (always),
 *                             the properties / yml files whose keys are compared globally
 * @param configRenames        old config key -> new one, on top of Spring Boot's own metadata; a key ending in
 *                             {@code .} renames the whole prefix ({@code spring.redis.: spring.data.redis.})
 * @param remotes              git URLs instead of server paths (cloned bare under work-dir, fetched before use)
 * @param jira                 where "create Jira issue" goes; {@code mock} keeps it in memory
 * @param extensionMappings    file formats the migration converted ({@code vm: ftl}): a changed {@code x.vm} is
 *                             looked up as {@code x.ftl} in repo2 and reported TEMPLATE_CONVERTED
 */
@Validated
@ConfigurationProperties("migration-risk")
public record MigrationRiskProperties(
        @NotEmpty List<Path> allowedRoots,
        @NotNull Path workDir,
        @Min(1) int maxChangedFiles,
        @Min(1) int maxCandidatesPerName,
        List<ApiRule> apiRules,
        List<String> directoryExcludes,
        String configFiles,
        Map<String, String> configRenames,
        Map<String, String> extensionMappings,
        Remotes remotes,
        Jira jira,
        Jobs jobs,
        Demo demo) {

    /** properties / yml under a resources/ or config/ directory: application config, not CI or chart files. */
    public static final String DEFAULT_CONFIG_FILES = "(.*/)?(resources|config)/.*\\.(properties|ya?ml)";

    public MigrationRiskProperties {
        apiRules = apiRules == null ? List.of() : List.copyOf(apiRules);
        directoryExcludes = directoryExcludes == null ? List.of() : List.copyOf(directoryExcludes);
        configFiles = configFiles == null || configFiles.isBlank() ? DEFAULT_CONFIG_FILES : configFiles;
        configRenames = configRenames == null ? Map.of() : Map.copyOf(configRenames);
        extensionMappings = extensionMappings == null ? Map.of("vm", "ftl") : Map.copyOf(extensionMappings);
        demo = demo == null ? new Demo(true) : demo;
        remotes = remotes == null ? new Remotes(List.of(), false, java.time.Duration.ofMinutes(1), List.of()) : remotes;
        jira = jira == null ? new Jira(true, null, "MIG", "Task", null, null) : jira;
        jobs = jobs == null ? new Jobs(2, 10, java.time.Duration.ofMinutes(30)) : jobs;
    }

    /**
     * Background analyses (the SSE endpoints).
     *
     * @param concurrency analyses running at once — each RefactoringMiner run is CPU- and memory-heavy
     * @param queue       accepted but waiting; beyond this a start is refused with 503 {@code busy}
     * @param retention   how long a finished job (and its result) stays fetchable
     */
    public record Jobs(@Min(1) int concurrency, @Min(0) int queue, java.time.Duration retention) {
    }

    /** One migration-sensitive API: a repo1 line repo2 lacks matching {@code pattern} is flagged MIGRATION_API. */
    public record ApiRule(@NotBlank String id, @NotBlank String pattern, String hint) {

        public Pattern compiled() {
            return Pattern.compile(pattern);
        }
    }

    public record Demo(boolean enabled) {
    }

    /**
     * Remote repositories: a {@code repo1Path} / {@code repo2Path} that is an {@code https://} (or {@code file://})
     * URL is cloned bare into {@code <work-dir>/remotes} once and fetched again when older than
     * {@code fetchInterval}. Only hosts in {@code allowedHosts} — the URL counterpart of {@code allowed-roots}.
     *
     * @param allowFileUrls {@code file://} URLs (tests, local mirrors); off by default
     * @param credentials   per host; the password is a token, from the environment, never from the request
     */
    public record Remotes(List<String> allowedHosts, boolean allowFileUrls, java.time.Duration fetchInterval,
                          List<Credential> credentials) {

        public Remotes {
            allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
            credentials = credentials == null ? List.of() : List.copyOf(credentials);
            fetchInterval = fetchInterval == null ? java.time.Duration.ofMinutes(1) : fetchInterval;
        }
    }

    public record Credential(@NotBlank String host, String username, String password) {
    }

    /**
     * Jira (REST API v2). {@code mock}: issues are numbered in memory and nothing leaves the process.
     *
     * @param browseUrl prefix for links in the page ({@code https://jira.acme.com/browse/}); default
     *                  {@code baseUrl + "/browse/"}
     */
    public record Jira(boolean mock, String baseUrl, String projectKey, String issueType, String token,
                       String browseUrl) {
    }
}
