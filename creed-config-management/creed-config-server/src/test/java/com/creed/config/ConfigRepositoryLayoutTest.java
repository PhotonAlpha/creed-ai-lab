package com.creed.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Guards the layered repository's shape. The server cannot: a misspelt directory ({@code env/sit01},
 * {@code type/micro-service}) is not an error to it, just a layer that silently never applies.
 *
 * <p>The allowed values below ARE the catalogue — a new environment or module type is added here and
 * as a directory, together.
 */
class ConfigRepositoryLayoutTest {

    static final Set<String> TYPES = Set.of("ms", "gateway", "proxy-gateway", "aggregator-ms");
    static final Set<String> ENV_GROUPS = Set.of("sit", "uat", "nft", "pdn");
    static final Set<String> ENVS = Set.of("sit1", "sit2", "sit3", "uat1", "uat2", "uat3", "nft", "pdn");
    static final List<String> LAYERS = List.of("common", "type", "env-group", "env", "app", "app-env");

    private static final Path REPO = Path.of("src/main/resources/config-repo");

    private static List<String> children(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> !n.equals(".DS_Store")).sorted().toList();
        }
    }

    @Test
    void theRootHoldsExactlyTheSixLayers() throws IOException {
        assertThat(children(REPO)).containsExactlyInAnyOrderElementsOf(
                Stream.concat(LAYERS.stream(), Stream.of("README.md")).toList());
    }

    @Test
    void layerDirectoriesAreNamedFromTheCatalogue() throws IOException {
        assertThat(TYPES).containsAll(children(REPO.resolve("type")));
        assertThat(ENV_GROUPS).containsAll(children(REPO.resolve("env-group")));
        assertThat(ENVS).containsAll(children(REPO.resolve("env")));
        // Every environment and group has a directory, even if it only names itself.
        assertThat(children(REPO.resolve("env"))).containsExactlyInAnyOrderElementsOf(ENVS);
        assertThat(children(REPO.resolve("env-group"))).containsExactlyInAnyOrderElementsOf(ENV_GROUPS);

        List<String> apps = children(REPO.resolve("app"));
        for (String app : children(REPO.resolve("app-env"))) {
            assertThat(apps).as("app-env/%s has no app/%s", app, app).contains(app);
            // Environments only: an app-env/<app>/<group>/ would also be picked up, after the
            // type value and before the env value — an order nobody would guess.
            assertThat(ENVS).as("app-env/%s/", app).containsAll(children(REPO.resolve("app-env").resolve(app)));
        }
    }

    @Test
    void everyLayerDirectoryHoldsOneApplicationYmlThatNamesItsLayer() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(REPO)) {
            files = s.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals(".DS_Store"))
                    .filter(p -> !p.equals(REPO.resolve("README.md")))
                    .toList();
        }
        assertThat(files).isNotEmpty();
        List<String> problems = new ArrayList<>();
        for (Path file : files) {
            Path rel = REPO.relativize(file);
            // Only application.yml: the server also reads {application}.yml and application-{profile}.yml
            // from every layer directory, and Spring Boot ranks those by NAME before LOCATION — a
            // type/ms/creed-resource-order.yml would beat env/ and app/. One name keeps the order the
            // location order.
            if (!rel.getFileName().toString().equals("application.yml")) {
                problems.add(rel + ": only application.yml is allowed in a layer directory");
                continue;
            }
            String layer = rel.getName(0).toString();
            int depth = switch (layer) {
                case "common" -> 1;
                case "app-env" -> 3;
                default -> 2;
            };
            if (rel.getNameCount() != depth + 1) {
                problems.add(rel + ": expected at depth " + depth + " under " + layer + "/");
            }
            if (!layer.equals(layerOf(file))) {
                problems.add(rel + ": creed.config.layer must be '" + layer + "'");
            }
        }
        assertThat(problems).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Object layerOf(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, Object> doc = new Yaml().load(reader);
            Map<String, Object> creed = (Map<String, Object>) doc.get("creed");
            Map<String, Object> config = creed == null ? null : (Map<String, Object>) creed.get("config");
            return config == null ? null : config.get("layer");
        }
    }
}
