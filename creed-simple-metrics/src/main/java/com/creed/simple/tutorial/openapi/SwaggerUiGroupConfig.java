package com.creed.simple.tutorial.openapi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.core.properties.AbstractSwaggerUiConfigProperties.SwaggerUrl;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.support.DefaultConversionService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Feeds Swagger UI from the specification: the "Select a definition" dropdown from the groups, and
 * the display options from the spec's {@code x-swagger-ui} block.
 *
 * <p>Done in code rather than through {@code springdoc.swagger-ui.urls} so the group list lives in
 * exactly one place - declaring a group in openapi.yaml is enough to make it appear in the
 * dropdown. The dropdown points at {@link OpenApiGroupController}, never at springdoc's own
 * {@code /v3/api-docs}, which is empty here because its scanner cannot see Camel REST DSL routes.
 */
@Configuration
@EnableConfigurationProperties(OpenApiGroupProperties.class)
public class SwaggerUiGroupConfig implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SwaggerUiGroupConfig.class);

    /** Options the spec may not set: this class derives them from the group list. */
    private static final Set<String> RESERVED = Set.of("urls", "urlsPrimaryName", "configUrl", "path");

    private final OpenApiGroupService groupService;
    private final ObjectProvider<SwaggerUiConfigProperties> swaggerUi;

    /**
     * Takes the service, not the properties: the groups may come from {@code x-groups} in the spec
     * rather than from application.yml, and only the service knows which source won. Depending on
     * it also orders this bean after the spec has been read.
     */
    public SwaggerUiGroupConfig(OpenApiGroupService groupService,
                                ObjectProvider<SwaggerUiConfigProperties> swaggerUi) {
        this.groupService = groupService;
        this.swaggerUi = swaggerUi;
    }

    @Override
    public void afterPropertiesSet() {
        // Absent when the UI is switched off (springdoc.swagger-ui.enabled=false, or api-docs
        // disabled, which takes SwaggerUiConfigProperties with it). The /openapi/*.yaml endpoints
        // keep working either way, so there is nothing to fail over.
        SwaggerUiConfigProperties config = swaggerUi.getIfAvailable();
        if (config == null) {
            log.info("[OPENAPI] swagger-ui is disabled; groups remain available under /openapi");
            return;
        }
        Set<SwaggerUrl> urls = new LinkedHashSet<>();
        for (OpenApiGroupProperties.Group group : groupService.groups()) {
            urls.add(new SwaggerUrl(group.getName(),
                    "/openapi/" + group.getName() + ".yaml",
                    group.getDisplayName()));
        }
        applySpecOptions(config);
        config.setUrls(urls);
        // Which definition the UI opens on. It must be the DISPLAY name: SwaggerUrl serialises
        // displayName as "name" (the real name field is @JsonIgnore), and Swagger UI matches
        // urls.primaryName against what it sees in the dropdown.
        groupService.groups().stream().findFirst()
                .ifPresent(first -> config.setUrlsPrimaryName(first.getDisplayName()));
        log.info("[OPENAPI] swagger-ui definitions: {}", urls.stream().map(SwaggerUrl::getName).toList());
    }

    /**
     * Copies the spec's {@code x-swagger-ui} block onto springdoc's configuration.
     *
     * <p>Only properties still unset are filled, which is what makes application.yml win: Boot
     * binds {@code springdoc.swagger-ui.*} before this runs, and every option on
     * {@link SwaggerUiConfigProperties} is a boxed type that stays null until something sets it.
     * So the spec carries the defaults that belong to the document (is the tag filter box useful
     * here? should operations start collapsed?) and a deployment can still override any of them.
     *
     * <p>The keys are the Swagger UI option names, camelCase, exactly as the JSON of
     * {@code /v3/api-docs/swagger-config} spells them.
     */
    private void applySpecOptions(SwaggerUiConfigProperties config) {
        Map<String, Object> options = groupService.swaggerUiOptions();
        if (options.isEmpty()) {
            return;
        }
        BeanWrapper wrapper = new BeanWrapperImpl(config);
        wrapper.setConversionService(DefaultConversionService.getSharedInstance());

        List<String> applied = new ArrayList<>();
        options.forEach((key, value) -> {
            if (RESERVED.contains(key)) {
                // Wiring this class owns; letting the document rewrite it would break the dropdown.
                log.warn("[OPENAPI] ignoring x-swagger-ui.{}: it is managed by the application", key);
                return;
            }
            if (!wrapper.isWritableProperty(key)) {
                log.warn("[OPENAPI] ignoring unknown x-swagger-ui option '{}'", key);
                return;
            }
            if (wrapper.getPropertyValue(key) != null) {
                log.info("[OPENAPI] x-swagger-ui.{} ignored: springdoc.swagger-ui.{} is set", key, key);
                return;
            }
            wrapper.setPropertyValue(key, value);
            applied.add(key + "=" + value);
        });
        log.info("[OPENAPI] swagger-ui options from the spec: {}", applied);
    }
}
