package com.creed.resource.veracode.service;

import com.creed.resource.veracode.client.jira.JiraIssue;
import com.creed.resource.veracode.client.jira.JiraVersion;
import com.creed.resource.veracode.domain.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

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
    void aFindingWithoutACveMatchesItsTitleAsAPhrase() {
        Finding sqli = finding(null, "SQL Injection in ReportQueryBuilder", "2.3.0");

        assertThat(JiraLookupService.summaryNames("[Veracode] sql injection in ReportQueryBuilder!", sqli)).isTrue();
        assertThat(JiraLookupService.summaryNames("SQL Injection in ReportQueryBuilderV2", sqli)).isFalse();
    }

    @Test
    void theVersionIsPartOfTheMatch() {
        JiraIssue issue = new JiraIssue("1", "CREED-1", new JiraIssue.Fields("CVE-2022-22965 Spring4Shell", null,
                List.of(JiraVersion.named("2.2.1")), List.of()));

        assertThat(JiraLookupService.covers(issue, finding("CVE-2022-22965", "x", "2.2.1"), "2.2.1")).isTrue();
        assertThat(JiraLookupService.covers(issue, finding("CVE-2022-22965", "x", "2.3.0"), "2.3.0")).isFalse();
    }

    @Test
    void jqlQuotesValuesAndStripsLuceneOperatorsFromPhrases() {
        String jql = Jql.lookup("CREED", Set.of("2.3.0"), List.of(
                finding("CVE-2021-44228", "x", "2.3.0"),
                finding(null, "Path \"traversal\" (RouterFunctions) *", "2.3.0")));

        assertThat(jql).isEqualTo("project = \"CREED\" AND affectedVersion in (\"2.3.0\") AND ("
                + "summary ~ \"\\\"CVE 2021 44228\\\"\" OR summary ~ \"\\\"Path traversal RouterFunctions\\\"\")");
        assertThat(Jql.quote("a\\b\"c")).isEqualTo("\"a\\\\b\\\"c\"");
    }

    private static Finding finding(String cve, String title, String version) {
        return new Finding((cve != null ? cve : title) + "@" + version, cve, title, List.of(), null, null, version,
                null, null, List.of());
    }
}
