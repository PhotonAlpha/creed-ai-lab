package com.creed.report.controller;

import com.creed.report.model.EnvironmentSnapshot;
import com.creed.report.model.RenderedEnvironment;
import com.creed.report.service.EnvironmentInspectionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the properties that <em>would</em> be activated at Spring Boot startup for a
 * given set of launch arguments, by assembling a throw-away {@code ConfigurableEnvironment}
 * and reading it back out — no beans, no web server spin-up.
 *
 * <p>Defaults match the launch arguments under inspection:
 * <pre>
 * -Dspring.profiles.active=adc,jpa,global
 * -Dspring.config.location=classpath:/,file:.../configs/global/,file:.../configs/application/,file:.../configs/application/creed-resource-catalog/
 * -Dspring.config.additional-location=file:.../configs/ssl/ssl-config.yml
 * </pre>
 * Every value can be overridden via query parameters.
 */
@RestController
public class EnvironmentInspectionController {

    private final EnvironmentInspectionService service;

    public EnvironmentInspectionController(EnvironmentInspectionService service) {
        this.service = service;
    }

    @GetMapping("/api/environment")
    public EnvironmentSnapshot environment(
            @RequestParam(name = "spring.profiles.active",
                    defaultValue = EnvironmentInspectionService.DEFAULT_PROFILES_ACTIVE) String profilesActive,
            @RequestParam(name = "spring.config.location",
                    defaultValue = EnvironmentInspectionService.DEFAULT_CONFIG_LOCATION) String configLocation,
            @RequestParam(name = "spring.config.additional-location",
                    defaultValue = EnvironmentInspectionService.DEFAULT_ADDITIONAL_LOCATION) String additionalLocation) {
        return service.inspect(profilesActive, configLocation, additionalLocation);
    }

    /**
     * The effective (active) properties only — sorted alphabetically by key and rendered
     * into both {@code .properties} and YAML text.
     */
    @GetMapping("/api/environment/rendered")
    public RenderedEnvironment rendered(
            @RequestParam(name = "spring.profiles.active",
                    defaultValue = EnvironmentInspectionService.DEFAULT_PROFILES_ACTIVE) String profilesActive,
            @RequestParam(name = "spring.config.location",
                    defaultValue = EnvironmentInspectionService.DEFAULT_CONFIG_LOCATION) String configLocation,
            @RequestParam(name = "spring.config.additional-location",
                    defaultValue = EnvironmentInspectionService.DEFAULT_ADDITIONAL_LOCATION) String additionalLocation) {
        return service.render(profilesActive, configLocation, additionalLocation);
    }
}
