package com.creed.report.controller;

import com.creed.report.model.EnvironmentSnapshot;
import com.creed.report.service.EnvironmentInspectionService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Renders the environment-inspection result as an HTML page. Backed by the same
 * {@link EnvironmentInspectionService} that powers {@code /api/environment}; the launch
 * arguments are editable on the page and submitted back as query parameters.
 */
@Controller
public class EnvironmentViewController {

    private final EnvironmentInspectionService service;

    public EnvironmentViewController(EnvironmentInspectionService service) {
        this.service = service;
    }

    @GetMapping("/environment")
    public String environment(
            @RequestParam(name = "spring.profiles.active",
                    defaultValue = EnvironmentInspectionService.DEFAULT_PROFILES_ACTIVE) String profilesActive,
            @RequestParam(name = "spring.config.location",
                    defaultValue = EnvironmentInspectionService.DEFAULT_CONFIG_LOCATION) String configLocation,
            @RequestParam(name = "spring.config.additional-location",
                    defaultValue = EnvironmentInspectionService.DEFAULT_ADDITIONAL_LOCATION) String additionalLocation,
            Model model) {

        EnvironmentSnapshot snapshot = service.inspect(profilesActive, configLocation, additionalLocation);

        model.addAttribute("profilesActive", profilesActive);
        model.addAttribute("configLocation", configLocation);
        model.addAttribute("additionalLocation", additionalLocation);
        model.addAttribute("snapshot", snapshot);
        return "environment";
    }
}
