package com.creed.report.service;

import com.creed.report.model.EnvironmentSnapshot;
import com.creed.report.model.PropertyEntry;
import com.creed.report.model.PropertySourceView;
import com.creed.report.model.RenderedEnvironment;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds a standalone {@link ConfigurableEnvironment} and applies the full Spring Boot
 * configuration-loading pipeline to it — {@code spring.profiles.active},
 * {@code spring.config.location}, {@code spring.config.additional-location} →
 * locate &amp; load config files → process profiles → order by precedence — without
 * creating a single bean or starting a web server.
 *
 * <p>The heavy lifting is done by {@link ConfigDataEnvironmentPostProcessor#applyTo},
 * the very same post-processor Spring Boot runs during a real application start. We feed
 * it a hand-built environment, let it resolve everything, then read the resulting
 * property sources back out.
 */
@Service
public class EnvironmentInspectionService {

    /** Name of the property source we seed the launch arguments into. */
    private static final String INPUT_SOURCE = "manualLaunchArguments";

    /**
     * The launch arguments from the task description, used as defaults when the caller
     * does not override them on the request.
     */
    public static final String DEFAULT_PROFILES_ACTIVE = "adc,jpa,global";
    public static final String DEFAULT_CONFIG_LOCATION =
            "classpath:/,"
            + "file:/Users/ethan/Desktop/workspace/sourcecode/github_space/creed-ai-lab/configs/global/,"
            + "file:/Users/ethan/Desktop/workspace/sourcecode/github_space/creed-ai-lab/configs/application/,"
            + "file:/Users/ethan/Desktop/workspace/sourcecode/github_space/creed-ai-lab/configs/application/creed-resource-catalog/";
    public static final String DEFAULT_ADDITIONAL_LOCATION =
            "file:/Users/ethan/Desktop/workspace/sourcecode/github_space/creed-ai-lab/configs/ssl/ssl-config.properties";

    /**
     * Build a fresh environment and run the Spring Boot config-data pipeline against it.
     *
     * @param profilesActive    value for {@code spring.profiles.active} (comma separated)
     * @param configLocation    value for {@code spring.config.location} (comma separated)
     * @param additionalLocation value for {@code spring.config.additional-location}
     * @return the populated environment, ready to be read
     */
    public ConfigurableEnvironment buildEnvironment(String profilesActive,
                                                    String configLocation,
                                                    String additionalLocation) {
        // 1. A plain, empty environment — no system property sources beyond the JVM
        //    defaults StandardEnvironment seeds (systemProperties / systemEnvironment).
        StandardEnvironment environment = new StandardEnvironment();

        // 2. Seed the launch arguments as the highest-priority source so the pipeline
        //    sees them exactly as if they had been passed on the command line / as -D args.
        Map<String, Object> input = new LinkedHashMap<>();
        if (StringUtils.hasText(profilesActive)) {
            input.put("spring.profiles.active", profilesActive);
        }
        if (StringUtils.hasText(configLocation)) {
            input.put("spring.config.location", configLocation);
        }
        if (StringUtils.hasText(additionalLocation)) {
            input.put("spring.config.additional-location", additionalLocation);
        }
        environment.getPropertySources().addFirst(new MapPropertySource(INPUT_SOURCE, input));

        // 3. Run the real Spring Boot pipeline: resolve locations, import files, activate
        //    profiles, and order everything by precedence — all onto our environment.
        ConfigDataEnvironmentPostProcessor.applyTo(
                environment,
                new DefaultResourceLoader(),
                new DefaultBootstrapContext());

        return environment;
    }

    /**
     * Build the environment and read every property back out into a serialisable snapshot:
     * the active/default profiles, each property source, and the effective (precedence
     * resolved) view of all properties.
     */
    public EnvironmentSnapshot inspect(String profilesActive,
                                       String configLocation,
                                       String additionalLocation) {
        ConfigurableEnvironment environment =
                buildEnvironment(profilesActive, configLocation, additionalLocation);

        List<PropertySourceView> sources = new ArrayList<>();
        // Effective view: first source to define a key wins, mirroring Spring's precedence.
        Map<String, PropertyEntry> effective = new LinkedHashMap<>();

        for (PropertySource<?> source : environment.getPropertySources()) {
            // The JVM's system properties / OS environment are not part of the config files
            // under inspection — exclude them from the report.
            if (StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME.equals(source.getName())
                    || StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME.equals(source.getName())
                    || INPUT_SOURCE.equals(source.getName())
            ) {
                continue;
            }
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                // Non-enumerable sources (rare here) cannot be listed key-by-key; skip the
                // listing but still record the source name for transparency.
                sources.add(new PropertySourceView(source.getName(), source.getClass().getName(), List.of()));
                continue;
            }

            List<PropertyEntry> entries = new ArrayList<>();
            for (String name : enumerable.getPropertyNames()) {
                Object raw = enumerable.getProperty(name);
                String resolved = resolve(environment, name, raw);
                boolean unresolved = resolved != null && resolved.contains("${");
                PropertyEntry entry = new PropertyEntry(name, stringify(raw), resolved, source.getName(), unresolved);
                entries.add(entry);
                effective.putIfAbsent(name, entry);
            }
            sources.add(new PropertySourceView(source.getName(), source.getClass().getName(), entries));
        }

        return new EnvironmentSnapshot(
                Arrays.asList(environment.getActiveProfiles()),
                Arrays.asList(environment.getDefaultProfiles()),
                sources,
                new ArrayList<>(effective.values()));
    }

    /** Resolve placeholders against the whole environment; fall back to the raw value. */
    private String resolve(ConfigurableEnvironment environment, String name, Object raw) {
        try {
            String value = environment.getProperty(name);
            return value != null ? value : stringify(raw);
        }
        catch (RuntimeException ex) {
            // Unresolvable placeholder (e.g. a secret not defined anywhere) — keep raw.
            return stringify(raw);
        }
    }

    private String stringify(Object value) {
        return value != null ? value.toString() : null;
    }

    // ------------------------------------------------------------------------
    // Rendering: sort the effective properties and emit .properties + YAML text
    // ------------------------------------------------------------------------

    /** Matches a {@code [n]} list index within a key segment. */
    private static final Pattern INDEX = Pattern.compile("\\[(\\d+)]");

    /**
     * Inspect the environment, then take the <em>effective</em> (active) properties,
     * sort them alphabetically by key, and render them both as a {@code .properties}
     * blob and as nested YAML.
     */
    public RenderedEnvironment render(String profilesActive,
                                      String configLocation,
                                      String additionalLocation) {
        EnvironmentSnapshot snapshot = inspect(profilesActive, configLocation, additionalLocation);

        List<PropertyEntry> sorted = snapshot.effective().stream()
                .sorted(Comparator.comparing(PropertyEntry::name))
                .collect(Collectors.toList());

        return new RenderedEnvironment(
                snapshot.activeProfiles(),
                sorted,
                toProperties(sorted),
                toYaml(sorted));
    }

    /** Render the entries as {@code key=value} lines (already sorted by caller). */
    private String toProperties(List<PropertyEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (PropertyEntry e : entries) {
            sb.append(e.name()).append('=').append(e.resolved() != null ? e.resolved() : "").append('\n');
        }
        return sb.toString();
    }

    /** Render the entries as nested YAML by expanding dotted / indexed keys into a tree. */
    private String toYaml(List<PropertyEntry> entries) {
        Map<String, Object> root = new LinkedHashMap<>();
        for (PropertyEntry e : entries) {
            insert(root, parsePath(e.name()), toScalar(e.resolved()));
        }

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        return new Yaml(options).dump(root);
    }

    /** Matches an integer; {@link #DECIMAL} additionally allows a fractional part. */
    private static final Pattern INTEGER = Pattern.compile("-?(0|[1-9]\\d*)");
    private static final Pattern DECIMAL = Pattern.compile("-?(0|[1-9]\\d*)\\.\\d+");

    /**
     * Convert a raw string value into the most natural scalar type for YAML, so numbers and
     * booleans render unquoted ({@code 5000}, {@code true}) instead of as quoted strings
     * ({@code '5000'}, {@code 'true'}). A single surrounding pair of quotes — a common
     * {@code .properties} authoring artifact, e.g. {@code password="${...}"} — is stripped so
     * the value is no longer force-quoted by YAML either.
     */
    private Object toScalar(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String v = stripSurroundingQuotes(value);
        if (v.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (v.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        if (INTEGER.matcher(v).matches()) {
            try {
                return Long.valueOf(v);
            }
            catch (NumberFormatException ignored) {
                // too large for long — fall through and keep it as a string
            }
        }
        if (DECIMAL.matcher(v).matches()) {
            return Double.valueOf(v);
        }
        return v;
    }

    /** Strip one matching pair of leading/trailing single or double quotes, if present. */
    private String stripSurroundingQuotes(String v) {
        if (v.length() >= 2) {
            char first = v.charAt(0);
            char last = v.charAt(v.length() - 1);
            if (first == last && (first == '"' || first == '\'')) {
                return v.substring(1, v.length() - 1);
            }
        }
        return v;
    }

    /** Split a property key into path segments: {@link String} keys and {@link Integer} list indices. */
    private List<Object> parsePath(String key) {
        List<Object> path = new ArrayList<>();
        for (String part : key.split("\\.")) {
            int bracket = part.indexOf('[');
            String name = bracket < 0 ? part : part.substring(0, bracket);
            if (!name.isEmpty()) {
                path.add(name);
            }
            if (bracket >= 0) {
                Matcher m = INDEX.matcher(part.substring(bracket));
                while (m.find()) {
                    path.add(Integer.parseInt(m.group(1)));
                }
            }
        }
        return path;
    }

    /**
     * Key under which a node's own scalar value is kept when that node ALSO has children
     * (e.g. both {@code a.b=x} and {@code a.b.c=y} exist). YAML cannot make one node both a
     * scalar and a map, so the scalar is parked under this readable marker rather than being
     * dropped. The {@code .properties} rendering still shows the original flat key losslessly.
     */
    private static final String SELF_VALUE_KEY = "(value)";

    /** Insert {@code value} into the map/list tree following {@code path}. */
    @SuppressWarnings("unchecked")
    private void insert(Object container, List<Object> path, Object value) {
        Object segment = path.get(0);
        boolean last = path.size() == 1;

        if (segment instanceof String key) {
            Map<String, Object> map = (Map<String, Object>) container;
            if (last) {
                putLeaf(map, key, value);
            }
            else {
                Object child = childFor(path.get(1), map.get(key));
                map.put(key, child);
                insert(child, path.subList(1, path.size()), value);
            }
        }
        else {
            int index = (Integer) segment;
            List<Object> list = (List<Object>) container;
            while (list.size() <= index) {
                list.add(null);
            }
            if (last) {
                Object existing = list.get(index);
                if (existing instanceof Map) {
                    ((Map<String, Object>) existing).put(SELF_VALUE_KEY, value);
                }
                else {
                    list.set(index, value);
                }
            }
            else {
                Object child = childFor(path.get(1), list.get(index));
                list.set(index, child);
                insert(child, path.subList(1, path.size()), value);
            }
        }
    }

    /**
     * Put a leaf value into a map. If the key already holds a child map (the node has
     * children too), keep the scalar under {@link #SELF_VALUE_KEY} instead of clobbering it.
     */
    @SuppressWarnings("unchecked")
    private void putLeaf(Map<String, Object> map, String key, Object value) {
        Object existing = map.get(key);
        if (existing instanceof Map) {
            ((Map<String, Object>) existing).put(SELF_VALUE_KEY, value);
        }
        else {
            map.put(key, value);
        }
    }

    /**
     * Pick (or reuse) the right child container for the next path segment. When turning an
     * existing scalar into a map (the node gains children), the scalar is preserved under
     * {@link #SELF_VALUE_KEY} so its value is never lost.
     */
    private Object childFor(Object nextSegment, Object existing) {
        if (nextSegment instanceof Integer) {
            return existing instanceof List ? existing : new ArrayList<>();
        }
        if (existing instanceof Map) {
            return existing;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        if (existing != null) {
            map.put(SELF_VALUE_KEY, existing);
        }
        return map;
    }
}
