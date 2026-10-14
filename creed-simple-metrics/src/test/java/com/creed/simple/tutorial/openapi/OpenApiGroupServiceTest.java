package com.creed.simple.tutorial.openapi;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The real {@code tutorial/openapi.yaml}: the six groups select what the tutorial guide says they do,
 * every group renders a self-contained document, and a misspelt tag stops startup.
 */
class OpenApiGroupServiceTest {

    private static OpenApiGroupService load(OpenApiGroupProperties properties) {
        OpenApiGroupService service = new OpenApiGroupService(properties, new DefaultResourceLoader());
        service.load();
        return service;
    }

    private static OpenApiGroupService load() {
        OpenApiGroupProperties properties = new OpenApiGroupProperties();
        properties.setSpec("classpath:tutorial/openapi.yaml");
        return load(properties);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rendered(OpenApiGroupService service, String group) {
        return new Yaml().load(service.render(service.findGroup(group).orElseThrow()));
    }

    @Test
    void eachGroupSelectsTheDocumentedPaths() {
        OpenApiGroupService service = load();
        Map<String, Integer> expected = Map.of(
                "all", 14, "business", 10, "mock", 4, "remote-calls", 8, "public", 10, "orders", 9);
        assertThat(service.groups()).extracting(OpenApiGroupProperties.Group::getName)
                .containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((group, count) ->
                assertThat(((Map<?, ?>) rendered(service, group).get("paths"))).as(group).hasSize(count));
    }

    @Test
    void pathsAreRelativeToTheServletContextOfThisModule() {
        Map<String, Object> all = rendered(load(), "all");
        assertThat(((Map<?, ?>) all.get("paths")).keySet()).allSatisfy(path ->
                assertThat((String) path).matches("/camel/api/v1/.*|/mock/.*"));
        assertThat((List<?>) all.get("servers")).first().extracting(s -> ((Map<?, ?>) s).get("url")).isEqualTo("/simple");
    }

    @Test
    void everyRenderedGroupIsSelfContainedAndCarriesNoPrivateExtensions() {
        OpenApiGroupService service = load();
        Pattern ref = Pattern.compile("#/components/schemas/([A-Za-z0-9_]+)");
        for (OpenApiGroupProperties.Group group : service.groups()) {
            String yaml = service.render(group);
            Map<String, Object> doc = new Yaml().load(yaml);
            Map<String, Object> components = child(doc, "components");
            Map<String, Object> schemas = child(components, "schemas");
            Matcher m = ref.matcher(yaml);
            while (m.find()) {
                assertThat(schemas).as("group %s: $ref %s", group.getName(), m.group(1)).containsKey(m.group(1));
            }
            assertThat(doc).as(group.getName()).doesNotContainKeys("x-groups", "x-swagger-ui");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @Test
    void aTagThatDoesNotExistFailsStartup() {
        OpenApiGroupProperties properties = new OpenApiGroupProperties();
        properties.setSpec("classpath:tutorial/openapi.yaml");
        OpenApiGroupProperties.Group typo = new OpenApiGroupProperties.Group();
        typo.setName("typo");
        typo.setTags(List.of("API-9 nope"));
        properties.setGroups(List.of(typo));

        assertThatIllegalStateException().isThrownBy(() -> load(properties))
                .withMessageContaining("API-9 nope");
    }
}
