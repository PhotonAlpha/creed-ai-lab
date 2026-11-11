package com.creed.resource.veracode.service;

import com.creed.resource.veracode.client.jira.JiraIssue;
import com.creed.resource.veracode.client.jira.JiraVersion;
import com.creed.resource.veracode.domain.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JiraMatchingTest {

    private static final String TEMPLATE = "{project} - {cve}|{cwe} - {file}";

    @Test
    void aCveMatchesAsAWholeTokenOnly() {
        Finding log4shell = finding("CVE-2021-44228", "CWE-502", "log4j-core-2.14.1.jar");

        assertThat(JiraLookupService.summaryNames("creed - CVE-2021-44228|CWE-502 - log4j-core-2.14.1.jar", log4shell)).isTrue();
        assertThat(JiraLookupService.summaryNames("upgrade log4j (cve-2021-44228)", log4shell)).isTrue();
        assertThat(JiraLookupService.summaryNames("CVE-2021-442281 something else", log4shell)).isFalse();
        assertThat(JiraLookupService.summaryNames("[Veracode] CVE-2021-44228", finding("CVE-2021-4422", null, "x.jar")))
                .isFalse();
    }

    @Test
    void theCveMatchIgnoresCaseAndSeparators() {
        Finding log4shell = finding("CVE-2021-44228", "CWE-502", "log4j-core-2.14.1.jar");

        assertThat(JiraLookupService.summaryNames("CREED log4j-core 2.14.1 cve 2021 44228", log4shell)).isTrue();
        assertThat(JiraLookupService.summaryNames("fix CVE_2021_44228 now", log4shell)).isTrue();
        assertThat(JiraLookupService.summaryNames("CVE202144228", log4shell)).isTrue();
        assertThat(JiraLookupService.summaryNames("cve 2021 442281", log4shell)).isFalse();
        assertThat(JiraLookupService.summaryNames("XCVE-2021-44228", log4shell)).isFalse();
    }

    @Test
    void anAdvisoryIdMatchesAsItsWordsAndAnUnknownByItsCwe() {
        Finding advisory = finding("SRCCLR-SID-29587", "CWE-400", "netty-codec-4.1.68.Final.jar");
        assertThat(JiraLookupService.summaryNames("creed - SRCCLR-SID-29587|CWE-400 - netty-codec-4.1.68.Final.jar", advisory))
                .isTrue();
        assertThat(JiraLookupService.summaryNames("creed - SRCCLR-SID-295870|CWE-400 - x", advisory)).isFalse();

        // no cve_id: "Unknown" and the CWE tell one Unknown from another
        Finding unknown = finding(null, "CWE-400", "netty-codec-4.1.68.Final.jar");
        assertThat(unknown.title()).isEqualTo("Unknown|CWE-400");
        assertThat(JiraLookupService.summaryNames("creed - Unknown|CWE-400 - netty-codec-4.1.68.Final.jar", unknown)).isTrue();
        assertThat(JiraLookupService.summaryNames("creed - Unknown|CWE-4000 - netty-codec-4.1.68.Final.jar", unknown)).isFalse();
        assertThat(JiraLookupService.summaryNames("creed - CVE-2022-1|CWE-400 - netty-codec-4.1.68.Final.jar", unknown)).isFalse();
    }

    @Test
    void theFileIsMatchedAsWholeWordsWhenTheTemplateWritesIt() {
        JiraIssue core = issue("creed - CVE-2021-44228|CWE-502 - log4j-core-2.14.1.jar", "2.3.0");
        Finding inCore = finding("CVE-2021-44228", "CWE-502", "log4j-core-2.14.1.jar");

        assertThat(JiraLookupService.covers(core, inCore, List.of(), TEMPLATE)).isTrue();
        // the same CVE in another jar, or in another version of it, is another issue
        assertThat(JiraLookupService.covers(core, finding("CVE-2021-44228", "CWE-502", "log4j-api-2.14.1.jar"), List.of(),
                TEMPLATE)).isFalse();
        assertThat(JiraLookupService.covers(issue("creed - CVE-2021-44228|CWE-502 - log4j-core-2.14.10.jar", "2.3.0"),
                inCore, List.of(), TEMPLATE)).isFalse();
        // a template without {file} never writes it, so it is not asked for
        assertThat(JiraLookupService.covers(issue("[Veracode] CVE-2021-44228", "2.3.0"), inCore, List.of(),
                "[Veracode] {cve}")).isTrue();
    }

    @Test
    void theVersionIsTheChosenJiraVersions() {
        JiraIssue issue = issue("creed - CVE-2022-22965|CWE-94 - spring-beans-5.3.17.jar", "2.2.1");
        Finding spring = finding("CVE-2022-22965", "CWE-94", "spring-beans-5.3.17.jar");

        assertThat(JiraLookupService.covers(issue, spring, List.of("2.2.1"), TEMPLATE)).isTrue();
        assertThat(JiraLookupService.covers(issue, spring, List.of("2.3.0"), TEMPLATE)).isFalse();
        // no version chosen on the page: any version covers it
        assertThat(JiraLookupService.covers(issue, spring, List.of(), TEMPLATE)).isTrue();
    }

    @Test
    void theComponentIsMatchedAsWholeWords() {
        Finding core = finding("CVE-2021-44228", "CWE-502", "log4j-core-2.14.1.jar", "log4j-core 2.14.1");

        assertThat(JiraLookupService.componentsNamed("creed-platform log4j-core 2.14.1 CVE-2021-44228", core)).isTrue();
        assertThat(JiraLookupService.componentsNamed("LOG4J_CORE 2.14.1: CVE-2021-44228", core)).isTrue();
        assertThat(JiraLookupService.componentsNamed("creed-platform log4j-api 2.14.1 CVE-2021-44228", core)).isFalse();
        assertThat(JiraLookupService.componentsNamed("creed-platform log4j-core 2.14.10 CVE-2021-44228", core)).isFalse();
    }

    @Test
    void jqlSearchesTheIdAsWrittenOrTheCweForAnUnknown() {
        Finding log4shell = finding("CVE-2021-44228", "CWE-502", "a.jar");
        Finding advisory = finding("SRCCLR-SID-29587", "CWE-400", "b.jar");
        Finding unknown = finding(null, "CWE-79", "c.jar");

        // one search for many findings: their terms OR'ed, the same CVE once
        assertThat(Jql.lookup("CREED", List.of("2.3.0"), List.of(log4shell, advisory, unknown, log4shell))).isEqualTo(
                "project = \"CREED\" AND affectedVersion in (\"2.3.0\") AND (summary ~ \"\\\"CVE-2021-44228\\\"\""
                        + " OR summary ~ \"\\\"SRCCLR SID 29587\\\"\" OR summary ~ \"\\\"CWE-79\\\"\") ORDER BY key");
        // nothing to search by
        assertThat(Jql.lookup("CREED", List.of("2.3.0"), List.of(finding(null, null, "d.jar")))).isNull();
        // several versions, or none (no version clause at all)
        assertThat(Jql.lookup("CREED", List.of("2.3.0", "2.4.0"), List.of(log4shell))).isEqualTo(
                "project = \"CREED\" AND affectedVersion in (\"2.3.0\", \"2.4.0\") AND (summary ~ \"\\\"CVE-2021-44228\\\"\") ORDER BY key");
        assertThat(Jql.lookup("CREED", List.of(), List.of(log4shell))).isEqualTo(
                "project = \"CREED\" AND (summary ~ \"\\\"CVE-2021-44228\\\"\") ORDER BY key");
        assertThat(Jql.quote("a\\b\"c")).isEqualTo("\"a\\\\b\\\"c\"");
    }

    @Test
    void jqlNarrowsToTheChosenCountriesByTheCustomFieldsNumber() {
        Finding log4shell = finding("CVE-2021-44228", "CWE-502", "a.jar");

        assertThat(Jql.lookup("CREED", List.of("2.3.0"), Jql.fieldRef("customfield_10300", "Country"),
                List.of("Singapore", "Hong Kong"), List.of(log4shell))).isEqualTo(
                "project = \"CREED\" AND affectedVersion in (\"2.3.0\") AND cf[10300] in (\"Singapore\", \"Hong Kong\")"
                        + " AND (summary ~ \"\\\"CVE-2021-44228\\\"\") ORDER BY key");
        // none chosen: no clause; a system field by its quoted name
        assertThat(Jql.lookup("CREED", List.of(), "cf[10300]", List.of(), List.of(log4shell)))
                .isEqualTo("project = \"CREED\" AND (summary ~ \"\\\"CVE-2021-44228\\\"\") ORDER BY key");
        assertThat(Jql.fieldRef("country", "Country")).isEqualTo("\"Country\"");
    }

    private static JiraIssue issue(String summary, String version) {
        return new JiraIssue("1", "CREED-1", new JiraIssue.Fields(summary, null, List.of(JiraVersion.named(version)),
                List.of(), null, null, null));
    }

    static Finding finding(String cveId, String cwe, String file, String... components) {
        String cve = cveId != null && cveId.startsWith("CVE-") ? cveId : null;
        return new Finding("app|" + cveId + "@" + file, "app", cveId, cve, cwe, file, List.of(components), null, null,
                "2.3.0", null, List.of());
    }
}
