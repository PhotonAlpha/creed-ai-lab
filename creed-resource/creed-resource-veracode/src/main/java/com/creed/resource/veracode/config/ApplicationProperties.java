package com.creed.resource.veracode.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/**
 * Veracode applications ({@code app_name}) under a name of their own and a link (asked for, 2026-11-10):
 * {@code GBT-T1-Creed-CCS} is shown as {@code Creed-CCS}, linking to its repository. The name is what the page, the
 * Jira description and the Confluence export show, and it is the application's Jira label (replacing the
 * {@code application-label} regex). An application without an entry keeps its {@code app_name} and has no link.
 *
 * <p>A list, not a map keyed by {@code app_name}: relaxed binding would drop characters from a map key.
 *
 * @param applications {@code app-name} (any case) -> {@code name}, {@code url}
 */
@ConfigurationProperties("veracode")
public record ApplicationProperties(List<Application> applications) {

    public ApplicationProperties {
        applications = applications == null ? List.of() : List.copyOf(applications);
    }

    /** @param url where the name links to; blank = no link */
    public record Application(String appName, String name, String url) {
    }

    /** The application's entry; {@code null} when it has none. */
    public Application of(String appName) {
        if (appName == null || appName.isBlank()) {
            return null;
        }
        String wanted = appName.trim().toLowerCase(Locale.ROOT);
        return applications.stream()
                .filter(a -> a.appName() != null && a.appName().trim().toLowerCase(Locale.ROOT).equals(wanted))
                .findFirst().orElse(null);
    }

    /** The mapped name, else the {@code app_name} itself; {@code null} without one. */
    public String name(String appName) {
        Application a = of(appName);
        if (a != null && a.name() != null && !a.name().isBlank()) {
            return a.name().trim();
        }
        return appName == null || appName.isBlank() ? null : appName.trim();
    }

    /** The mapped link; {@code null} when the application has none. */
    public String url(String appName) {
        Application a = of(appName);
        return a == null || a.url() == null || a.url().isBlank() ? null : a.url().trim();
    }
}
