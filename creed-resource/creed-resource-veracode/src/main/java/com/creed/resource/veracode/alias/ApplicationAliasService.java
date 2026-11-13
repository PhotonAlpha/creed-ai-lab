package com.creed.resource.veracode.alias;

import com.creed.resource.veracode.domain.ApplicationAlias;
import com.creed.resource.veracode.domain.ApplicationAliases;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The application aliases, edited on the report-templates page and kept in {@code application_alias} (asked for,
 * 2026-11-13: they were {@code veracode.applications} in the configuration, changed only by a redeploy).
 *
 * <p>Every finding asks for its application's alias - the page, the Jira description and label, the Confluence export -
 * so the table is held in memory and read again only after the page saves it. One instance: another instance sees a
 * save at its next start.
 *
 * <p>{@code veracode.applications} still in a configuration (an external config directory, say) is imported at start,
 * the app names the table does not have yet, with a warning to remove it: the table is what is read.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApplicationAliasService implements ApplicationAliases, ApplicationRunner {

    static final String LEGACY_PROPERTY = "veracode.applications";

    private final ApplicationAliasRepository repository;
    private final Environment environment;
    private final Clock clock;
    private final TransactionTemplate tx;

    /**
     * The table as last read. Loaded and replaced under this object's lock, a write's under it until committed: a
     * reader can never put back what a save just replaced.
     */
    private volatile List<ApplicationAlias> cached;

    @Override
    public List<ApplicationAlias> all() {
        List<ApplicationAlias> aliases = cached;
        if (aliases != null) {
            return aliases;
        }
        synchronized (this) {
            if (cached == null) {
                cached = List.copyOf(repository.list());
            }
            return cached;
        }
    }

    /**
     * The page's whole table, replacing the stored one - the page edits and saves it as one list.
     *
     * @throws IllegalArgumentException a row without an app name, an app name twice (any case), a row with neither an
     *                                  alias nor a link, a link that is not http(s) - nothing is saved then
     */
    public synchronized List<ApplicationAlias> replace(List<ApplicationAlias> aliases) {
        List<ApplicationAlias> clean = validate(aliases == null ? List.of() : aliases);
        tx.executeWithoutResult(status -> {
            repository.deleteAll();
            clean.forEach(a -> repository.insert(a, clock.instant()));
        });
        cached = List.copyOf(repository.list());
        log.info("application aliases saved: {}", clean.size());
        return cached;
    }

    static List<ApplicationAlias> validate(List<ApplicationAlias> aliases) {
        List<ApplicationAlias> clean = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < aliases.size(); i++) {
            ApplicationAlias a = aliases.get(i);
            String row = "row " + (i + 1);
            String appName = trim(a == null ? null : a.appName());
            if (appName == null) {
                throw new IllegalArgumentException(row + ": the Veracode application name is required");
            }
            if (!seen.add(ApplicationAliases.key(appName))) {
                throw new IllegalArgumentException(row + ": '" + appName + "' is listed twice");
            }
            String alias = trim(a.alias());
            String url = trim(a.repositoryUrl());
            if (alias == null && url == null) {
                throw new IllegalArgumentException(row + ": give '" + appName + "' an alias or a repository URL");
            }
            if (url != null && !isHttp(url)) {
                throw new IllegalArgumentException(row + ": the repository URL must be http(s), got '" + url + "'");
            }
            clean.add(new ApplicationAlias(appName, alias, url));
        }
        return clean;
    }

    private static boolean isHttp(String url) {
        try {
            URI uri = URI.create(url);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** {@code veracode.applications} -> the table: the app names it lacks. */
    @Override
    public synchronized void run(ApplicationArguments args) {
        List<Legacy> legacy = Binder.get(environment).bind(LEGACY_PROPERTY, Bindable.listOf(Legacy.class)).orElse(List.of());
        if (legacy.isEmpty()) {
            return;
        }
        Set<String> present = new HashSet<>();
        repository.list().forEach(a -> present.add(ApplicationAliases.key(a.appName())));
        List<ApplicationAlias> missing = new ArrayList<>();
        for (Legacy l : legacy) {
            String appName = trim(l.appName());
            if (appName != null && present.add(ApplicationAliases.key(appName))
                    && (trim(l.name()) != null || trim(l.url()) != null)) {
                missing.add(new ApplicationAlias(appName, trim(l.name()), trim(l.url())));
            }
        }
        tx.executeWithoutResult(status -> missing.forEach(a -> repository.insert(a, clock.instant())));
        List<String> imported = missing.stream().map(ApplicationAlias::appName).toList();
        cached = null;
        log.warn("{} is no longer read: application aliases are edited on the report-templates page. Imported {} new "
                + "of {} entries ({}); remove the property from the configuration", LEGACY_PROPERTY, imported.size(),
                legacy.size(), imported);
    }

    /** One entry of the old {@code veracode.applications}. */
    public record Legacy(String appName, String name, String url) {
    }
}
