package com.creed.resource.veracode.service;

import com.creed.resource.veracode.domain.Finding;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * The JQL that narrows one batch of findings (one affected version) to the issues that may cover them:
 *
 * <pre>
 * project = "CREED" AND affectedVersion = "2.3.0"
 *   AND (summary ~ "\"CVE 2021 44228\"" OR summary ~ "\"CVE 2022 22965\"" OR summary ~ "srcclr sid 29587")
 *   ORDER BY key
 * </pre>
 *
 * <p>{@code summary ~} is Lucene full-text search, not a substring test: the phrase is tokenised (so
 * {@code CVE-2021-44228} is the phrase {@code cve 2021 44228}), stop words are dropped, and an unquoted
 * {@code CVE-2021-44228} would mean "cve AND NOT 2021 AND NOT 44228". The query therefore only narrows the
 * candidates; {@link JiraLookupService#covers} decides the match (components included) on the issues it returns.
 * Components are not in the query: library names are tokenised too ({@code log4j-core} = {@code log4j core}),
 * and the CVE plus the version already leave a handful of issues.
 *
 * <p>A CVE is a phrase (its parts in order). A title (an advisory id) is plain words, so a summary that has
 * them in another order is still a candidate. Those words are lower-cased: unquoted, an upper-case
 * {@code AND} / {@code OR} / {@code NOT} from a title would be a Lucene operator.
 *
 * <p>Lucene's reserved characters are replaced by spaces in a search phrase rather than escaped - they
 * are token separators anyway, and JQL escaping of a backslash inside a quoted phrase inside a quoted
 * string is three layers deep.
 */
final class Jql {

    private static final String LUCENE_RESERVED = "[+\\-&|!(){}\\[\\]^\"~*?:\\\\/]";

    private Jql() {
    }

    /** {@code null} when none of the findings has anything to search by. */
    static String lookup(String projectKey, String version, Collection<Finding> findings) {
        List<String> terms = findings.stream().map(Jql::summaryTerm).filter(t -> !t.isEmpty()).distinct()
                .map(t -> "summary ~ " + quote(t)).toList();
        if (terms.isEmpty()) {
            return null;
        }
        return "project = " + quote(projectKey)
                + " AND affectedVersion = " + quote(version)
                + " AND (" + String.join(" OR ", terms) + ")"
                + " ORDER BY key";
    }

    private static String summaryTerm(Finding f) {
        String text = searchPhrase(f.matchText());
        if (text.isEmpty()) {
            return "";
        }
        return f.cve() != null ? "\"" + text + "\"" : text.toLowerCase(Locale.ROOT);
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
