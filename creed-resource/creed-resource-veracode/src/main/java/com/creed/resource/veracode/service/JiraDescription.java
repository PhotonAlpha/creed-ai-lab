package com.creed.resource.veracode.service;

import com.creed.resource.veracode.api.dto.SastIssueView;
import com.creed.resource.veracode.config.ApplicationProperties;
import com.creed.resource.veracode.domain.Finding;
import com.creed.resource.veracode.domain.SastFlaw;
import com.creed.resource.veracode.domain.SastIssue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A created issue's description, in Jira wiki markup (the v2 API's format). */
final class JiraDescription {

    private JiraDescription() {
    }

    /**
     * One table, the page's columns in the page's order (the title's {@code cve_id|cwe_id} as two rows); the
     * {@code cve_summary} is its last row, in full. The application is shown by its mapped name, linked
     * ({@code veracode.applications}).
     */
    static String of(Finding f, ReportView report, ApplicationProperties apps) {
        StringBuilder d = new StringBuilder("h3. Veracode SCA finding\n");
        d.append("||Field||Value||\n");
        // not one "cve|cwe" cell: | ends a cell, and Jira Cloud renders an escaped \| as a backslash and a new cell
        row(d, "CVE", f.cve() != null
                ? "[" + f.cve() + "|https://nvd.nist.gov/vuln/detail/" + f.cve() + "]" : escape(f.cveText()));
        row(d, "CWE", cwe(f));
        row(d, "Veracode application", application(f.application(), apps));
        row(d, "Library file", escape(f.fileName()));
        row(d, "Component(s)", escape(String.join(", ", f.components())));
        row(d, "Severity", escape(f.severity()));
        row(d, "CVSS", f.cvss() == null ? "" : f.cvss().toString());
        row(d, "Affected version", escape(f.affectedVersion()));
        row(d, "Location", escape(String.join("; ", f.locations())));
        row(d, "Description", escape(f.description()));
        d.append("\nSource: ").append(source(report)).append("\n");
        return d.toString();
    }

    /**
     * Where the report came from (asked for, 2026-11-10): a Confluence attachment is linked by its URL, and the page
     * holding it too (asked for, 2026-11-10) - the attachment's own page is where its context lives; an uploaded
     * file is attached to the issue itself, after it is created. Then the report inside it, the scan and the policy.
     */
    static String source(ReportView report) {
        StringBuilder d = new StringBuilder();
        String name = report.sourceName() != null ? report.sourceName() : report.fileName();
        if (report.sourceUrl() != null) {
            d.append("report Confluence attachment [").append(escape(name)).append('|').append(report.sourceUrl()).append(']');
            if (report.sourcePageUrl() != null) {
                String page = report.sourcePageTitle() == null || report.sourcePageTitle().isBlank()
                        ? report.sourcePageUrl() : report.sourcePageTitle();
                d.append(" on page [").append(escape(page)).append('|').append(report.sourcePageUrl()).append(']');
            }
        } else {
            d.append("uploaded report ").append(escape(name)).append(" (attached to this issue)");
        }
        if (report.fileName() != null && !report.fileName().equals(name)) {
            d.append(", report ").append(escape(report.fileName()));
        }
        if (report.scanVersion() != null) {
            d.append(", scan ").append(escape(report.scanVersion()));
        }
        if (report.policyName() != null) {
            d.append(", policy ").append(escape(report.policyName()));
        }
        return d.toString();
    }

    /** The mapped name, a link when the mapping has one; the {@code app_name} as text otherwise. */
    static String application(String appName, ApplicationProperties apps) {
        String name = apps.name(appName);
        String url = apps.url(appName);
        return name == null ? null : url == null ? escape(name) : "[" + escape(name) + "|" + url + "]";
    }

    /**
     * A SAST issue: the CWE and category, then one row per open flaw (application, severity, file:line, module, Veracode
     * issue id, first found), the first flaw's description - Veracode words it per CWE, so the flaws share it - and
     * the source of every report among the issue's applications.
     */
    /**
     * @param project {@code sast.project-name}: the head's {@code <name>: <project> - <category>} line
     */
    static String ofSast(SastIssue issue, List<ReportView> reports, ApplicationProperties apps, String project) {
        StringBuilder d = new StringBuilder();
        // first what the page's "Jira summary" column shows (asked for, 2026-11-12) - three lines per application:
        //   Creed-CCS: IDB VERACODE STSA - OS Command Injection / CWE 78 <name> / CWE 78 (Flaw IDs: 2)
        // the summary itself stays one line: it is what the issue is found by
        for (SastIssueView.ApplicationBlock block : SastIssueView.blocks(issue, project, apps)) {
            d.append(block.url() == null ? escape(block.name()) : "[" + escape(block.name()) + "|" + block.url() + "]")
                    .append(": ").append(escape(block.heading())).append('\n')
                    .append(escape(block.cwe())).append('\n')
                    .append(escape(block.flawIds())).append("\n\n");
        }
        d.append("h3. Veracode SAST finding\n");
        d.append("||Field||Value||\n");
        row(d, "CWE", issue.cwe() != null && issue.cwe().matches("CWE-\\d+")
                ? "[" + issue.cwe() + "|https://cwe.mitre.org/data/definitions/" + issue.cwe().substring(4) + ".html]"
                : escape(issue.cweText()));
        row(d, "CWE name", escape(issue.cweName()));
        row(d, "Category", escape(issue.categoryText()));
        row(d, "Highest severity", escape(issue.severity()));
        row(d, "Veracode application(s)", String.join(", ",
                issue.applications().stream().map(a -> application(a, apps)).toList()));
        row(d, "Open flaws", String.valueOf(issue.flaws().size()));
        d.append("\n||Application||Severity||File||Line||Module||Issue ID||First found||\n");
        for (SastFlaw f : issue.flaws()) {
            String application = application(f.application(), apps);
            d.append('|').append(application == null ? " " : application).append('|').append(cell(f.severity()))
                    .append('|').append(cell(f.sourceFile())).append('|').append(f.line() == null ? " " : f.line())
                    .append('|').append(cell(f.module())).append('|').append(cell(f.issueId()))
                    .append('|').append(cell(f.firstFound())).append("|\n");
        }
        String description = issue.flaws().stream().map(SastFlaw::description)
                .filter(t -> t != null && !t.isBlank()).findFirst().orElse(null);
        if (description != null) {
            d.append("\nh4. Description\n").append(escape(description)).append('\n');
        }
        Set<String> sources = new LinkedHashSet<>();
        reports.stream().filter(r -> issue.applications().contains(r.appName())).forEach(r -> sources.add(source(r)));
        if (!sources.isEmpty()) {
            d.append('\n');
            sources.forEach(line -> d.append("Source: ").append(line).append('\n'));
        }
        return d.toString();
    }

    private static String cell(String value) {
        return value == null || value.isBlank() ? " " : escape(value.replaceAll("\\s*\\R\\s*", " "));
    }

    /** {@code CWE-502} links to MITRE's entry; "Unknown" stays text. */
    private static String cwe(Finding f) {
        if (f.cwe() != null && f.cwe().matches("CWE-\\d+")) {
            return "[" + f.cwe() + "|https://cwe.mitre.org/data/definitions/" + f.cwe().substring(4) + ".html]";
        }
        return escape(f.cweText());
    }

    private static void row(StringBuilder d, String field, String value) {
        // a line break would end the table row: a multi-line summary is kept to one
        String cell = value == null || value.isBlank() ? " " : value.replaceAll("\\s*\\R\\s*", " ");
        d.append('|').append(field).append('|').append(cell).append("|\n");
    }

    /**
     * {@code [ ] { }} start links/macros in wiki markup: escaped. {@code |} ends a table cell, and Jira Cloud does not
     * honour {@code \|} in a cell (it shows the backslash and splits the cell): it becomes {@code ¦}, which reads alike.
     */
    private static String escape(String text) {
        return text == null ? null : text.replace("|", "¦").replaceAll("([\\[\\]{}])", "\\\\$1");
    }
}
