package com.creed.resource.veracode.service;

import com.creed.resource.veracode.config.ApplicationProperties;
import com.creed.resource.veracode.domain.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JiraDescriptionTest {

    private static final ReportView REPORT = new ReportView("r.xml", "app", null, "2.3.0", "2.3.0", false, null, null,
            null, null, null, "r.xml", null, null, null);
    private static final ApplicationProperties APPS = new ApplicationProperties(List.of(
            new ApplicationProperties.Application("GBT-T1-Creed-CCS", "Creed-CCS", "https://example.com/projects/CCS/browse")));

    @Test
    void noPipeInACellSplitsTheRow() {
        Finding f = new Finding("app|Unknown|CWE-79@a.jar", "app", null, null, "CWE-79", "a.jar", List.of("a 1"), "High",
                7.5, "2.3.0", "Input | output [mixed]\nsecond line", List.of("a 1 (x.war)"));

        String d = JiraDescription.of(f, REPORT, APPS);

        // Jira Cloud shows "\|" as a backslash and a new cell: CVE and CWE are two rows, a pipe in text is ¦
        assertThat(d).doesNotContain("\\|")
                .contains("|CVE|Unknown|\n", "|CWE|[CWE-79|https://cwe.mitre.org/data/definitions/79.html]|\n",
                        "|Description|Input ¦ output \\[mixed\\] second line|\n");
        // every row has exactly its two cells
        d.lines().filter(l -> l.startsWith("|") && !l.startsWith("||")).forEach(l ->
                assertThat(l.replaceAll("\\[[^\\]]*\\|[^\\]]*\\]", "LINK").chars().filter(c -> c == '|').count())
                        .as(l).isEqualTo(3));
    }

    @Test
    void theApplicationByItsMappedNameAndWhereTheReportCameFrom() {
        Finding f = new Finding("GBT-T1-Creed-CCS|CVE-2021-44228@a.jar", "GBT-T1-Creed-CCS", "CVE-2021-44228",
                "CVE-2021-44228", "CWE-502", "a.jar", List.of("a 1"), "High", 10.0, "2.3.0", "x", List.of());
        ReportView uploaded = new ReportView("reports.zip/r.xml", "GBT-T1-Creed-CCS", null, "scan 7", "2.3.0", false,
                "Policy", null, null, null, null, "reports.zip", null, null, null);
        ReportView downloaded = new ReportView("veracode_09_Nov_2026.zip/r.xml", "other-app", null, null, null, false,
                null, null, null, null, null, "veracode_09_Nov_2026.zip", "https://wiki/download/attachments/1/v.zip",
                "Creed | Veracode reports", "https://wiki/pages/viewpage.action?pageId=1");

        // mapped: its name, linked; the uploaded zip is attached to the issue, the report in it named
        assertThat(JiraDescription.of(f, uploaded, APPS))
                .contains("|Veracode application|[Creed-CCS|https://example.com/projects/CCS/browse]|\n")
                .contains("Source: uploaded report reports.zip (attached to this issue), report reports.zip/r.xml, "
                        + "scan scan 7, policy Policy\n");
        // from Confluence: the attachment is linked; an application without a mapping is its app_name
        assertThat(JiraDescription.source(downloaded)).isEqualTo("report Confluence attachment "
                + "[veracode_09_Nov_2026.zip|https://wiki/download/attachments/1/v.zip] on page "
                + "[Creed ¦ Veracode reports|https://wiki/pages/viewpage.action?pageId=1], report veracode_09_Nov_2026.zip/r.xml");
        assertThat(JiraDescription.application("other-app", APPS)).isEqualTo("other-app");
    }
}
