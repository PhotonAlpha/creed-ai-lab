package com.creed.resource.veracode.service;

import com.creed.resource.veracode.domain.Finding;

import java.util.Locale;

/**
 * The issue picker's two inputs for one finding: the {@code currentJQL} and the {@code query}.
 *
 * <pre>
 * currentJQL  project = "CREED" AND affectedVersion = "2.3.0" AND summary ~ "\"CVE 2021 44228\""
 * query       CVE 2021 44228
 * </pre>
 *
 * <p>{@code summary ~} is Lucene full-text search, not a substring test: the phrase is tokenised (so
 * {@code CVE-2021-44228} is the phrase {@code cve 2021 44228}), stop words are dropped, and an unquoted
 * {@code CVE-2021-44228} would mean "cve AND NOT 2021 AND NOT 44228". Both inputs therefore only narrow
 * the candidates; {@link JiraLookupService} decides the match on the issues it then reads.
 *
 * <p>A CVE is a phrase (its parts in order). A title is plain words, so a summary that has them in another
 * order is still a candidate - the title match is order-free. Those words are lower-cased: unquoted, an
 * upper-case {@code AND} / {@code OR} / {@code NOT} from a title would be a Lucene operator.
 *
 * <p>Lucene's reserved characters are replaced by spaces in a search phrase rather than escaped - they
 * are token separators anyway, and JQL escaping of a backslash inside a quoted phrase inside a quoted
 * string is three layers deep. The picker's {@code query} is that phrase as words: the picker matches each
 * word against the start of the summary's words (or the key).
 */
final class Jql {

    private static final String LUCENE_RESERVED = "[+\\-&|!(){}\\[\\]^\"~*?:\\\\/]";

    private Jql() {
    }

    /** {@code null} when the finding has nothing to search by. */
    static String lookup(String projectKey, String version, Finding finding) {
        String term = summaryTerm(finding);
        if (term.isEmpty()) {
            return null;
        }
        return "project = " + quote(projectKey)
                + " AND affectedVersion = " + quote(version)
                + " AND summary ~ " + quote(term);
    }

    static String pickerQuery(Finding finding) {
        return searchPhrase(finding.matchText());
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
