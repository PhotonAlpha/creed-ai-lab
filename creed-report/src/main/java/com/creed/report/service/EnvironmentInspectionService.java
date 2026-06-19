package com.creed.report.service;

import com.creed.report.model.EnvironmentSnapshot;
import com.creed.report.model.PropertyEntry;
import com.creed.report.model.PropertySourceView;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            "file:/Users/ethan/Desktop/workspace/sourcecode/github_space/creed-ai-lab/configs/ssl/ssl-config.yml";

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
}
