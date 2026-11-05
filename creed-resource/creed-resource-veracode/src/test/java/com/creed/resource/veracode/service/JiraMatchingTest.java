package com.creed.resource.veracode.service;

import com.creed.resource.veracode.client.jira.JiraIssue;
import com.creed.resource.veracode.client.jira.JiraVersion;
import com.creed.resource.veracode.domain.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JiraMatchingTest {

    @Test
    void aCveMatchesAsAWholeTokenOnly() {
        Finding log4shell = finding("CVE-2021-44228", "Log4Shell", "2.3.0");

        assertThat(JiraLookupService.summaryNames("[Veracode] CVE-2021-44228 Log4j", log4shell)).isTrue();
        assertThat(JiraLookupService.summaryNames("upgrade log4j (cve-2021-44228)", log4shell)).isTrue();
        assertThat(JiraLookupService.summaryNames("CVE-2021-442281 something else", log4shell)).isFalse();
        assertThat(JiraLookupService.summaryNames("CVE-2021-4422 prefix", finding("CVE-2021-4422", "x", "2.3.0")))
                .isTrue();
        assertThat(JiraLookupService.summaryNames("[Veracode] CVE-2021-44228", finding("CVE-2021-4422", "x", "2.3.0")))
                .isFalse();
    }

    @Test
    void theCveMatchIgnoresCaseAndSeparators() {
        Finding log4shell = finding("CVE-2021-44228", "Log4Shell", "2.3.0");

        assertThat(JiraLookupService.summaryNames("CREED log4j-core 2.14.1 cve 2021 44228", log4shell)).isTrue();
        assertThat(JiraLookupService.summaryNames("fix CVE_2021_44228 now", log4shell)).isTrue();
        assertThat(JiraLookupService.summaryNames("CVE202144228", log4shell)).isTrue();
        assertThat(JiraLookupService.summaryNames("cve 2021 442281", log4shell)).isFalse();
        assertThat(JiraLookupService.summaryNames("XCVE-2021-44228", log4shell)).isFalse();
    }

    @Test
    void aFindingWithoutACveMatchesItsTitleAsAPhrase() {
        Finding sqli = finding(null, "SQL Injection in ReportQueryBuilder", "2.3.0");

        assertThat(JiraLookupService.summaryNames("[Veracode] sql injection in ReportQueryBuilder!", sqli)).isTrue();
        assertThat(JiraLookupService.summaryNames("SQL Injection in ReportQueryBuilderV2", sqli)).isFalse();
        // any order: the words, not the phrase
        assertThat(JiraLookupService.summaryNames("CREED ReportQueryBuilder.java: injection (SQL) in ReportQueryBuilder", sqli))
                .isTrue();
        assertThat(JiraLookupService.summaryNames("SQL Injection in ReportBuilder", sqli)).isFalse();
    }

    @Test
    void theVersionIsPartOfTheMatch() {
        JiraIssue issue = new JiraIssue("1", "CREED-1", new JiraIssue.Fields("CVE-2022-22965 Spring4Shell", null,
                List.of(JiraVersion.named("2.2.1")), List.of(), null, null, null));

        assertThat(JiraLookupService.covers(issue, finding("CVE-2022-22965", "x", "2.2.1"), "2.2.1")).isTrue();
        assertThat(JiraLookupService.covers(issue, finding("CVE-2022-22965", "x", "2.3.0"), "2.3.0")).isFalse();
    }

    @Test
    void jqlQuotesValuesAndStripsLuceneOperatorsFromPhrases() {
        Finding log4shell = finding("CVE-2021-44228", "x", "2.3.0");
        Finding traversal = finding(null, "Path \"traversal\" (RouterFunctions) * OR more", "2.3.0");

        // a CVE is a phrase; a title is lower-cased words, so its "OR" is not an operator
        assertThat(Jql.lookup("CREED", "2.3.0", log4shell)).isEqualTo(
                "project = \"CREED\" AND affectedVersion = \"2.3.0\" AND summary ~ \"\\\"CVE 2021 44228\\\"\"");
        assertThat(Jql.lookup("CREED", "2.3.0", traversal)).isEqualTo(
                "project = \"CREED\" AND affectedVersion = \"2.3.0\" AND summary ~ \"path traversal routerfunctions or more\"");
        assertThat(Jql.lookup("CREED", "2.3.0", finding(null, "***", "2.3.0"))).isNull();
        // the picker's query: the same words, nothing the query parser reads
        assertThat(Jql.pickerQuery(log4shell)).isEqualTo("CVE 2021 44228");
        assertThat(Jql.quote("a\\b\"c")).isEqualTo("\"a\\\\b\\\"c\"");
    }

    private static Finding finding(String cve, String title, String version) {
        return new Finding((cve != null ? cve : title) + "@" + version, cve, title, List.of(), null, null, version,
                null, null, List.of());
    }
}
