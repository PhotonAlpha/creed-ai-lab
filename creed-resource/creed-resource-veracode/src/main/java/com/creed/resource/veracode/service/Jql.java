package com.creed.resource.veracode.service;

import com.creed.resource.veracode.domain.Finding;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the lookup JQL. One query per batch of findings:
 *
 * <pre>
 * project = "CREED" AND affectedVersion in ("2.3.0", "2.4.0")
 *   AND (summary ~ "\"CVE 2021 44228\"" OR summary ~ "\"SQL Injection in ReportQueryBuilder\"")
 * </pre>
 *
 * <p>{@code summary ~} is Lucene full-text search, not a substring test: the phrase is tokenised (so
 * {@code CVE-2021-44228} is the phrase {@code cve 2021 44228}), stop words are dropped, and an unquoted
 * {@code CVE-2021-44228} would mean "cve AND NOT 2021 AND NOT 44228". The query therefore only narrows
 * the candidates; {@link JiraLookupService} decides the match on the returned summaries.
 *
 * <p>Lucene's reserved characters are replaced by spaces in a search phrase rather than escaped - they
 * are token separators anyway, and JQL escaping of a backslash inside a quoted phrase inside a quoted
 * string is three layers deep.
 */
final class Jql {

    private static final String LUCENE_RESERVED = "[+\\-&|!(){}\\[\\]^\"~*?:\\\\/]";

    private Jql() {
    }

    static String lookup(String projectKey, Collection<String> versions, List<Finding> findings) {
        String versionList = versions.stream().map(Jql::quote).collect(Collectors.joining(", "));
        String summaries = findings.stream()
                .map(f -> searchPhrase(f.matchText()))
                .filter(p -> !p.isEmpty())
                .distinct()
                .map(p -> "summary ~ " + quote("\"" + p + "\""))
                .collect(Collectors.joining(" OR "));
        return "project = " + quote(projectKey)
                + " AND affectedVersion in (" + versionList + ")"
                + " AND (" + summaries + ")";
    }

    /** The text as Lucene will tokenise it, with nothing in it that means something to the query parser. */
    static String searchPhrase(String text) {
        return text == null ? "" : text.replaceAll(LUCENE_RESERVED, " ").replaceAll("\\s+", " ").trim();
    }

    /** A JQL string literal. */
    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
