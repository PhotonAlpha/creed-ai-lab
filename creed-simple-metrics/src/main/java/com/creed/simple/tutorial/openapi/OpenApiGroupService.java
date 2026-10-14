package com.creed.simple.tutorial.openapi;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Serves group-specific views of the single OpenAPI document.
 *
 * <p>springdoc's scanner only understands {@code @RestController}; the endpoints of this project
 * are declared in Camel's REST DSL (see camel/rest-api.xml), so nothing is generated from the
 * routes. The hand-written {@code openapi.yaml} is the source of truth instead, and a group is
 * produced by filtering it at request time - so there is exactly one document to keep in sync.
 *
 * <p>A group states its criteria - {@code tags}, {@code paths}, {@code methods},
 * {@code excludeInternal} - and they are ANDed, an empty one meaning "no constraint". Operations
 * that do not match are removed, then paths that lost every operation are dropped, the top-level
 * {@code tags} list is pruned to what survived, and the schemas no remaining operation still
 * references are garbage-collected (transitively, since a schema may {@code $ref} another).
 */
@Service
public class OpenApiGroupService {

    private static final Logger log = LoggerFactory.getLogger(OpenApiGroupService.class);

    private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";
    /** Keys of a path item that are operations rather than metadata such as parameters. */
    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    /** Root extension of the spec holding the group definitions; see the comment in openapi.yaml. */
    private static final String GROUPS_EXTENSION = "x-groups";

    /** Operation-level extension marking an endpoint that {@code excludeInternal} groups drop. */
    private static final String INTERNAL_EXTENSION = "x-internal";

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final OpenApiGroupProperties properties;
    private final ResourceLoader resourceLoader;

    /** The parsed spec, read once at startup: it ships inside the jar and cannot change at runtime. */
    private Map<String, Object> spec;

    /** Groups in effect, from application.yml when it defines any, otherwise from the spec. */
    private List<OpenApiGroupProperties.Group> groups;

    public OpenApiGroupService(OpenApiGroupProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    void load() {
        Resource resource = resourceLoader.getResource(properties.getSpec());
        try (InputStream in = resource.getInputStream()) {
            this.spec = new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the OpenAPI spec at " + properties.getSpec(), e);
        }
        log.info("[OPENAPI] loaded {} ({} paths, {} schemas)",
                properties.getSpec(), paths().size(), schemas().size());

        this.groups = resolveGroups();

        // Fail loudly on a typo in a group's tags rather than serving a silently empty document.
        Set<String> known = knownTags();
        for (OpenApiGroupProperties.Group group : groups) {
            List<String> unknown = group.getTags().stream().filter(t -> !known.contains(t)).toList();
            if (!unknown.isEmpty()) {
                throw new IllegalStateException("group '" + group.getName() + "' refers to tag(s) "
                        + unknown + " that do not exist in " + properties.getSpec() + "; known tags: " + known);
            }
            // Render it once at startup: a group that selects nothing is almost always a wrong
            // path pattern or method, and finding that out here beats finding out from an empty
            // page. Only a warning though - unlike a misspelled tag it can be deliberate.
            int selected = ((Map<?, ?>) filter(group).get("paths")).size();
            if (selected == 0) {
                log.warn("[OPENAPI] group '{}' selects no path at all: {}",
                        group.getName(), group.describeCriteria());
            }
            log.info("[OPENAPI] group '{}' -> {} ({} path(s))",
                    group.getName(), group.describeCriteria(), selected);
        }
    }

    /**
     * Groups declared by {@code x-groups} in the spec, unless application.yml overrides them.
     *
     * <p>The spec is the natural home: a group is a list of tags, and the tags are defined a few
     * lines below it in the same file. application.yml still wins when it lists any group, so a
     * deployment can publish a different selection without touching the packaged document.
     */
    @SuppressWarnings("unchecked")
    private List<OpenApiGroupProperties.Group> resolveGroups() {
        if (!properties.getGroups().isEmpty()) {
            log.info("[OPENAPI] groups taken from creed.tutorial.openapi.groups (overriding {} in the spec)",
                    GROUPS_EXTENSION);
            return properties.getGroups();
        }

        List<OpenApiGroupProperties.Group> fromSpec = new ArrayList<>();
        if (spec.get(GROUPS_EXTENSION) instanceof List<?> declared) {
            for (Object entry : declared) {
                Map<String, Object> map = (Map<String, Object>) entry;
                OpenApiGroupProperties.Group group = new OpenApiGroupProperties.Group();
                group.setName(String.valueOf(map.get("name")));
                Object displayName = map.get("displayName");
                group.setDisplayName(displayName == null ? null : String.valueOf(displayName));
                group.setTags(new ArrayList<>((List<String>) map.getOrDefault("tags", List.of())));
                group.setPaths(new ArrayList<>((List<String>) map.getOrDefault("paths", List.of())));
                group.setMethods(new ArrayList<>((List<String>) map.getOrDefault("methods", List.of())));
                group.setExcludeInternal(Boolean.TRUE.equals(map.get("excludeInternal")));
                fromSpec.add(group);
            }
        }

        if (fromSpec.isEmpty()) {
            // Neither source defined anything; serving the whole document beats serving nothing.
            OpenApiGroupProperties.Group all = new OpenApiGroupProperties.Group();
            all.setName("all");
            all.setDisplayName("All endpoints");
            log.warn("[OPENAPI] no {} in the spec and no creed.tutorial.openapi.groups; falling back to a "
                     + "single '{}' group", GROUPS_EXTENSION, all.getName());
            return List.of(all);
        }
        log.info("[OPENAPI] groups taken from {} in {}", GROUPS_EXTENSION, properties.getSpec());
        return fromSpec;
    }

    public List<OpenApiGroupProperties.Group> groups() {
        return groups;
    }

    /**
     * The {@code x-swagger-ui} block of the spec: Swagger UI display options that travel with the
     * document rather than with the deployment. Empty when the spec declares none.
     *
     * @see SwaggerUiGroupConfig
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> swaggerUiOptions() {
        return spec.get("x-swagger-ui") instanceof Map<?, ?> options
                ? (Map<String, Object>) options
                : Map.of();
    }

    public Optional<OpenApiGroupProperties.Group> findGroup(String name) {
        return groups.stream()
                .filter(g -> g.getName().equalsIgnoreCase(name))
                .findFirst();
    }

    /** Renders the group's view of the spec as YAML. */
    public String render(OpenApiGroupProperties.Group group) {
        return toYaml(filter(group));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> filter(OpenApiGroupProperties.Group group) {
        Map<String, Object> out = new LinkedHashMap<>(spec);
        // Both extensions describe how this project publishes the spec, not what it documents, so
        // neither reaches a reader. (x-internal IS left in place: unlike these it says something
        // about the operation itself.)
        out.remove(GROUPS_EXTENSION);
        out.remove("x-swagger-ui");

        if (group.isUnconstrained()) {
            out.put("info", titled(group));
            return out;
        }

        Map<String, Object> keptPaths = new LinkedHashMap<>();
        Set<String> keptTags = new LinkedHashSet<>();
        paths().forEach((path, item) -> {
            Map<String, Object> pathItem = (Map<String, Object>) item;
            Map<String, Object> keptItem = new LinkedHashMap<>();
            pathItem.forEach((key, value) -> {
                if (!HTTP_METHODS.contains(key.toLowerCase(Locale.ROOT))) {
                    keptItem.put(key, value);  // shared parameters, summary, servers ...
                    return;
                }
                Map<String, Object> operation = (Map<String, Object>) value;
                if (keeps(group, path, key, operation)) {
                    keptItem.put(key, value);
                    keptTags.addAll((List<String>) operation.getOrDefault("tags", List.of()));
                }
            });
            // A path item holding nothing but metadata means every operation was filtered out.
            if (keptItem.keySet().stream().anyMatch(k -> HTTP_METHODS.contains(k.toLowerCase(Locale.ROOT)))) {
                keptPaths.put(path, keptItem);
            }
        });

        out.put("info", titled(group));
        out.put("paths", keptPaths);
        if (spec.containsKey("tags")) {
            List<Map<String, Object>> tags = ((List<Map<String, Object>>) spec.get("tags")).stream()
                    .filter(t -> keptTags.contains(String.valueOf(t.get("name"))))
                    .toList();
            out.put("tags", tags);
        }
        pruneSchemas(out, keptPaths);
        return out;
    }

    /**
     * Decides whether one operation belongs to a group.
     *
     * <p>The criteria are ANDed and an empty one is not a constraint, so {@code tags} alone behaves
     * exactly as before, {@code paths} alone selects by URL, and the two together mean "this tag
     * under that path".
     */
    @SuppressWarnings("unchecked")
    private boolean keeps(OpenApiGroupProperties.Group group, String path, String method,
                          Map<String, Object> operation) {
        if (group.isExcludeInternal() && Boolean.TRUE.equals(operation.get(INTERNAL_EXTENSION))) {
            return false;
        }
        if (!group.getTags().isEmpty()) {
            List<String> tags = (List<String>) operation.getOrDefault("tags", List.of());
            if (tags.stream().noneMatch(group.getTags()::contains)) {
                return false;
            }
        }
        if (!group.getPaths().isEmpty()
            && group.getPaths().stream().noneMatch(pattern -> PATH_MATCHER.match(pattern, path))) {
            return false;
        }
        return group.getMethods().isEmpty()
               || group.getMethods().stream().anyMatch(m -> m.equalsIgnoreCase(method));
    }

    /** Keeps only the schemas still reachable from the retained paths. */
    @SuppressWarnings("unchecked")
    private void pruneSchemas(Map<String, Object> out, Map<String, Object> keptPaths) {
        Map<String, Object> all = schemas();
        if (all.isEmpty()) {
            return;
        }
        Set<String> reachable = new LinkedHashSet<>();
        Deque<Object> queue = new ArrayDeque<>();
        queue.add(keptPaths);
        while (!queue.isEmpty()) {
            for (String name : refsIn(queue.poll())) {
                if (reachable.add(name) && all.containsKey(name)) {
                    queue.add(all.get(name));  // a schema may reference further schemas
                }
            }
        }
        Map<String, Object> kept = new LinkedHashMap<>();
        all.forEach((name, schema) -> {
            if (reachable.contains(name)) {
                kept.put(name, schema);
            }
        });
        Map<String, Object> components = new LinkedHashMap<>((Map<String, Object>) out.get("components"));
        components.put("schemas", kept);
        out.put("components", components);
    }

    /** Collects the schema names referenced by one node, without descending into nested schemas. */
    @SuppressWarnings("unchecked")
    private static List<String> refsIn(Object node) {
        List<String> found = new ArrayList<>();
        Deque<Object> queue = new ArrayDeque<>();
        queue.add(node);
        while (!queue.isEmpty()) {
            Object current = queue.poll();
            if (current instanceof Map<?, ?> map) {
                map.forEach((key, value) -> {
                    if ("$ref".equals(key) && value instanceof String ref && ref.startsWith(SCHEMA_REF_PREFIX)) {
                        found.add(ref.substring(SCHEMA_REF_PREFIX.length()));
                    } else {
                        queue.add(value);
                    }
                });
            } else if (current instanceof Collection<?> list) {
                queue.addAll((Collection<Object>) list);
            }
        }
        return found;
    }

    /** Appends the group name to the title, so the Swagger UI tab says which definition is shown. */
    private Map<String, Object> titled(OpenApiGroupProperties.Group group) {
        Map<String, Object> info = new LinkedHashMap<>(info());
        info.put("title", info.getOrDefault("title", "API") + " - " + group.getDisplayName());
        return info;
    }

    private static String toYaml(Map<String, Object> document) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setSplitLines(false);
        options.setIndent(2);
        return new Yaml(options).dump(document);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> paths() {
        return (Map<String, Object>) spec.getOrDefault("paths", Map.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> info() {
        return (Map<String, Object>) spec.getOrDefault("info", Map.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> schemas() {
        Map<String, Object> components = (Map<String, Object>) spec.getOrDefault("components", Map.of());
        return (Map<String, Object>) components.getOrDefault("schemas", Map.of());
    }

    @SuppressWarnings("unchecked")
    private Set<String> knownTags() {
        Set<String> tags = new LinkedHashSet<>();
        paths().values().forEach(item -> ((Map<String, Object>) item).forEach((key, value) -> {
            if (HTTP_METHODS.contains(key.toLowerCase(Locale.ROOT))) {
                tags.addAll((List<String>) ((Map<String, Object>) value).getOrDefault("tags", List.of()));
            }
        }));
        return tags;
    }
}
