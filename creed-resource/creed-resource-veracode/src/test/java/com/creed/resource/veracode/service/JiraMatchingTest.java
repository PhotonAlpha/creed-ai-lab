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

        assertThat(JiraLookupService.covers(issue, finding("CVE-2022-22965", "x", "2.2.1"), "2.2.1", false)).isTrue();
        assertThat(JiraLookupService.covers(issue, finding("CVE-2022-22965", "x", "2.3.0"), "2.3.0", false)).isFalse();
    }

    @Test
    void theComponentIsMatchedAsWholeWords() {
        Finding core = new Finding("CVE-2021-44228@2.3.0", "CVE-2021-44228", "Log4Shell", List.of("log4j-core 2.14.1"),
                null, null, "2.3.0", null, null, List.of());

        assertThat(JiraLookupService.componentsNamed("creed-platform log4j-core 2.14.1 CVE-2021-44228", core)).isTrue();
        // case and separators ignored, as the template may have been edited by hand
        assertThat(JiraLookupService.componentsNamed("LOG4J_CORE 2.14.1: CVE-2021-44228", core)).isTrue();
        assertThat(JiraLookupService.componentsNamed("creed-platform log4j-api 2.14.1 CVE-2021-44228", core)).isFalse();
        assertThat(JiraLookupService.componentsNamed("creed-platform log4j-core 2.14.10 CVE-2021-44228", core)).isFalse();
        assertThat(JiraLookupService.componentsNamed("creed-platform 2.14.1 log4j-core CVE-2021-44228", core)).isFalse();
        // a finding in two components: an issue for both covers it, one for either alone does not
        Finding two = new Finding(core.id(), core.cve(), core.title(), List.of("log4j-core 2.14.1", "log4j-api 2.14.1"),
                null, null, "2.3.0", null, null, List.of());
        assertThat(JiraLookupService.componentsNamed("app log4j-core 2.14.1, log4j-api 2.14.1 CVE-2021-44228", two)).isTrue();
        assertThat(JiraLookupService.componentsNamed("app log4j-core 2.14.1 CVE-2021-44228", two)).isFalse();
    }

    @Test
    void jqlQuotesValuesAndStripsLuceneOperatorsFromPhrases() {
        Finding log4shell = finding("CVE-2021-44228", "x", "2.3.0");
        Finding traversal = finding(null, "Path \"traversal\" (RouterFunctions) * OR more", "2.3.0");

        // a CVE is a phrase; a title is lower-cased words, so its "OR" is not an operator
        // one search for many findings: their terms OR'ed, the same CVE once
        assertThat(Jql.lookup("CREED", "2.3.0", List.of(log4shell, traversal, log4shell))).isEqualTo(
                "project = \"CREED\" AND affectedVersion = \"2.3.0\" AND (summary ~ \"\\\"CVE 2021 44228\\\"\""
                        + " OR summary ~ \"path traversal routerfunctions or more\") ORDER BY key");
        assertThat(Jql.lookup("CREED", "2.3.0", List.of(finding(null, "***", "2.3.0")))).isNull();
        assertThat(Jql.quote("a\\b\"c")).isEqualTo("\"a\\\\b\\\"c\"");
    }

    private static Finding finding(String cve, String title, String version) {
        return new Finding((cve != null ? cve : title) + "@" + version, cve, title, List.of(), null, null, version,
                null, null, List.of());
    }
}
