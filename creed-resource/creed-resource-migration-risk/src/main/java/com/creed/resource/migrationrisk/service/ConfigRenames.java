package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.config.MigrationRiskProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Config keys Spring Boot renamed — so "repo2 is missing {@code spring.redis.host}" can become "repo2 has it as
 * {@code spring.data.redis.host}".
 *
 * <p>Two sources: every {@code META-INF/spring-configuration-metadata.json} on this service's classpath (Boot
 * keeps a deprecated key with its {@code replacement} for a release or two — about 260 in 4.1.1), and
 * {@code migration-risk.config-renames} for what the metadata no longer carries (Boot 3.0's renames are gone from
 * Boot 4's jars) or a team's own keys. A configured name ending in {@code .} renames a whole prefix. Configured
 * entries win over the metadata.
 */
@Component
@Slf4j
public class ConfigRenames {

    private final Map<String, String> exact = new HashMap<>();
    /** Longest prefix first, so a specific rule beats a general one. */
    private final List<Map.Entry<String, String>> prefixes;

    public ConfigRenames(MigrationRiskProperties properties, JsonMapper json) {
        int fromMetadata = 0;
        try {
            for (Resource r : new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:META-INF/spring-configuration-metadata.json")) {
                try (InputStream in = r.getInputStream()) {
                    for (JsonNode p : json.readTree(in).path("properties")) {
                        String replacement = p.path("deprecation").path("replacement").asString(null);
                        if (replacement != null && !replacement.isBlank()) {
                            exact.put(p.path("name").asString(), replacement);
                            fromMetadata++;
                        }
                    }
                } catch (RuntimeException e) {
                    log.debug("unreadable configuration metadata {}: {}", r, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("cannot scan configuration metadata: {}", e.getMessage());
        }
        properties.configRenames().forEach((from, to) -> {
            if (!from.endsWith(".")) {
                exact.put(from, to);
            }
        });
        this.prefixes = properties.configRenames().entrySet().stream().filter(e -> e.getKey().endsWith("."))
                .sorted(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed())
                .map(e -> Map.entry(e.getKey(), e.getValue())).toList();
        log.info("config renames: {} from Spring Boot metadata, {} configured", fromMetadata, properties.configRenames().size());
    }

    /**
     * The key's new name, if it was renamed. Works on the global names {@link ConfigDiff#globalKeys} produces:
     * a {@code [dev] } scope prefix is kept as is.
     */
    public Optional<String> renamed(String globalKey) {
        String scope = "";
        String key = globalKey;
        if (globalKey.startsWith("[")) {
            int end = globalKey.indexOf("] ");
            if (end > 0) {
                scope = globalKey.substring(0, end + 2);
                key = globalKey.substring(end + 2);
            }
        }
        String to = exact.get(key);
        if (to == null) {
            for (Map.Entry<String, String> p : prefixes) {
                if (key.startsWith(p.getKey())) {
                    to = p.getValue() + key.substring(p.getKey().length());
                    break;
                }
            }
        }
        return to == null || to.equals(key) ? Optional.empty() : Optional.of(scope + to);
    }
}
