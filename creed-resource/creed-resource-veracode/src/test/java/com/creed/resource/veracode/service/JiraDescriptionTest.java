package com.creed.resource.veracode.service;

import com.creed.resource.veracode.domain.ApplicationAliases;
import com.creed.resource.veracode.domain.Finding;
import com.creed.resource.veracode.domain.SastFlaw;
import com.creed.resource.veracode.domain.SastIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JiraDescriptionTest {

    private static final ReportView REPORT = new ReportView("r.xml", "app", null, "2.3.0", "2.3.0", false, null, null,
            null, null, null, "r.xml", null, null, null);
    private static final ApplicationAliases APPS = ApplicationAliases.of(List.of(
            new com.creed.resource.veracode.domain.ApplicationAlias("GBT-T1-Creed-CCS", "Creed-CCS", "https://example.com/projects/CCS/browse")));

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

    private static Finding log4shell(String application, String severity) {
        return new Finding(application + "|CVE-2021-44228@log4j-core-2.14.1.jar", application, "CVE-2021-44228",
                "CVE-2021-44228", "CWE-502", "log4j-core-2.14.1.jar", List.of("log4j-core 2.14.1"), severity, 10.0,
                "2.3.0", "JNDI lookup", List.of());
    }

    private static ReportView report(String application, String file) {
        return new ReportView(file, application, null, "1", "2.3.0", false, null, null, null, null, null, file, null,
                null, null);
    }

    /**
     * Labelling another application's finding onto an issue (asked for, 2026-11-13): the analysis' applications get
     * new tables, an application filed from an earlier analysis keeps its own, text that is not a table goes.
     */
    @Test
    void anScaDescriptionKeepsTheTablesOfApplicationsTheAnalysisDoesNotHold() {
        String old = "written by hand\n\n"
                + JiraDescription.of(log4shell("legacy-app", "Very High"), report("legacy-app", "legacy.xml"), APPS) + "\n"
                + JiraDescription.of(log4shell("app", "Medium"), report("app", "old.xml"), APPS);

        String merged = JiraDescription.mergeSca(old, List.of(log4shell("app", "High"),
                log4shell("GBT-T1-Creed-CCS", "High")), f -> report(f.application(), "new.xml"), APPS);

        assertThat(merged.split("h3. Veracode SCA finding", -1)).hasSize(4);
        assertThat(merged).doesNotContain("written by hand", "|Severity|Medium|", "old.xml")
                .startsWith("h3. Veracode SCA finding");
        // in the old order, the new application last
        assertThat(merged.indexOf("|Veracode application|legacy-app|"))
                .isLessThan(merged.indexOf("|Veracode application|app|"));
        assertThat(merged.indexOf("|Veracode application|app|"))
                .isLessThan(merged.indexOf("|Veracode application|[Creed-CCS|https://example.com/projects/CCS/browse]|"));
        assertThat(merged).contains("uploaded report legacy.xml");
        // nothing to keep: as a list of new tables
        assertThat(JiraDescription.mergeSca(null, List.of(log4shell("app", "High")), f -> REPORT, APPS))
                .isEqualTo(JiraDescription.of(log4shell("app", "High"), REPORT, APPS));
    }

    private static SastFlaw flaw(String application, String id, String severity) {
        return new SastFlaw(application, id, severity, "CWE-89", "SQL Injection", "SQL Injection", "m.jar",
                "Dao.java", 12, null, null, null, false, false, true, "2026-11-01", "Built from input");
    }

    @Test
    void aSastDescriptionKeepsWhatItHoldsOfApplicationsTheAnalysisDoesNot() {
        SastIssue before = SastIssue.group(List.of(flaw("legacy-app", "7", "Very High"), flaw("app", "1", "Medium")))
                .getFirst();
        String old = JiraDescription.ofSast(before, List.of(report("legacy-app", "legacy.xml"), report("app", "old.xml")),
                APPS, "creed-sast");
        SastIssue now = SastIssue.group(List.of(flaw("app", "2", "Medium"), flaw("GBT-T1-Creed-CCS", "3", "Medium")))
                .getFirst();

        String merged = JiraDescription.mergeSast(old, now, List.of(report("app", "new.xml"),
                report("GBT-T1-Creed-CCS", "ccs.xml")), APPS, "creed-sast");

        // the head: this analysis' blocks, then the kept one
        assertThat(merged).contains("app: creed-sast - SQL Injection", "Creed-CCS|https://example.com/projects/CCS/browse]: ",
                "legacy-app: creed-sast - SQL Injection\nCWE 89 SQL Injection\nCWE 89 (Flaw IDs: 7)");
        assertThat(merged.indexOf("legacy-app: creed-sast")).isLessThan(merged.indexOf("h3. Veracode SAST finding"));
        // the table: every application, all their open flaws, the highest severity among them
        assertThat(merged).contains("|Open flaws|3|", "|Highest severity|Very High|",
                "|Veracode application(s)|app, [Creed-CCS|https://example.com/projects/CCS/browse], legacy-app|",
                "|legacy-app|Very High|Dao.java|12|m.jar|7|2026-11-01|", "|app|Medium|Dao.java|12|m.jar|2|",
                "Source (legacy-app): uploaded report legacy.xml", "Source (app): uploaded report new.xml",
                "Source ([Creed-CCS|https://example.com/projects/CCS/browse]): uploaded report ccs.xml");
        // the analysis' application replaced: its old flaw and source are gone
        assertThat(merged).doesNotContain("|app|Medium|Dao.java|12|m.jar|1|", "old.xml");
        assertThat(merged.split("h3. Veracode SAST finding", -1)).hasSize(2);
        // an old description this service did not write: nothing to keep
        assertThat(JiraDescription.mergeSast("written by hand", now, List.of(), APPS, "creed-sast"))
                .isEqualTo(JiraDescription.ofSast(now, List.of(), APPS, "creed-sast"));
    }
}
