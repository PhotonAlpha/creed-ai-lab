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
 * @param maxChangedFiles      upstream ranges with more changed files are refused
 * @param maxCandidatesPerName how many same-named repo2 files are pulled in to catch a moved class
 * @param directoryExcludes    folder mode: directory names skipped at any depth (VCS data, build output, IDE files)
 * @param configFiles          regex over repo paths: besides Spring's application* / bootstrap* files (always),
 *                             the properties / yml files whose keys are compared globally
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
        Map<String, String> extensionMappings,
        Jobs jobs,
        Demo demo) {

    /** properties / yml under a resources/ or config/ directory: application config, not CI or chart files. */
    public static final String DEFAULT_CONFIG_FILES = "(.*/)?(resources|config)/.*\\.(properties|ya?ml)";

    public MigrationRiskProperties {
        apiRules = apiRules == null ? List.of() : List.copyOf(apiRules);
        directoryExcludes = directoryExcludes == null ? List.of() : List.copyOf(directoryExcludes);
        configFiles = configFiles == null || configFiles.isBlank() ? DEFAULT_CONFIG_FILES : configFiles;
        extensionMappings = extensionMappings == null ? Map.of("vm", "ftl") : Map.copyOf(extensionMappings);
        demo = demo == null ? new Demo(true) : demo;
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

    /** One migration-sensitive API: an added upstream line matching {@code pattern} is flagged HIGH. */
    public record ApiRule(@NotBlank String id, @NotBlank String pattern, String hint) {

        public Pattern compiled() {
            return Pattern.compile(pattern);
        }
    }

    public record Demo(boolean enabled) {
    }
}
