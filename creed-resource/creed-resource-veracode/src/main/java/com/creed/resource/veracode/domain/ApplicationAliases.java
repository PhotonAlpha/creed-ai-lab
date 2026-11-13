package com.creed.resource.veracode.domain;

import java.util.List;
import java.util.Locale;

/**
 * The {@link ApplicationAlias}es in force: what the page, the Jira description and label, the Confluence export and the
 * report templates call an application. An application without an alias is shown, and labelled, as its {@code app_name}
 * and has no link. The service ({@code ApplicationAliasService}) reads the table; {@link #of(List)} is a fixed set.
 */
public interface ApplicationAliases {

    ApplicationAliases NONE = List::of;

    List<ApplicationAlias> all();

    static ApplicationAliases of(List<ApplicationAlias> aliases) {
        List<ApplicationAlias> fixed = List.copyOf(aliases);
        return () -> fixed;
    }

    /** The application's alias; {@code null} when it has none. */
    default ApplicationAlias of(String appName) {
        if (appName == null || appName.isBlank()) {
            return null;
        }
        String wanted = key(appName);
        return all().stream().filter(a -> a.appName() != null && key(a.appName()).equals(wanted)).findFirst().orElse(null);
    }

    /** The alias, else the {@code app_name} itself; {@code null} without one. */
    default String name(String appName) {
        ApplicationAlias a = of(appName);
        if (a != null && a.alias() != null && !a.alias().isBlank()) {
            return a.alias().trim();
        }
        return appName == null || appName.isBlank() ? null : appName.trim();
    }

    /** The repository link; {@code null} when the application has none. */
    default String url(String appName) {
        ApplicationAlias a = of(appName);
        return a == null || a.repositoryUrl() == null || a.repositoryUrl().isBlank() ? null : a.repositoryUrl().trim();
    }

    /** How an {@code app_name} is compared: trimmed, any case. */
    static String key(String appName) {
        return appName.trim().toLowerCase(Locale.ROOT);
    }
}
