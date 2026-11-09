package com.creed.resource.veracode.service;

import com.creed.resource.veracode.domain.Finding;
import com.creed.resource.veracode.domain.SastFlaw;
import com.creed.resource.veracode.domain.SastIssue;

/** A created issue's description, in Jira wiki markup (the v2 API's format). */
final class JiraDescription {

    private JiraDescription() {
    }

    /**
     * One table, the page's columns in the page's order (the title's {@code cve_id|cwe_id} as two rows); the
     * {@code cve_summary} is its last row, in full.
     */
    static String of(Finding f, ReportView report) {
        StringBuilder d = new StringBuilder("h3. Veracode SCA finding\n");
        d.append("||Field||Value||\n");
        // not one "cve|cwe" cell: | ends a cell, and Jira Cloud renders an escaped \| as a backslash and a new cell
        row(d, "CVE", f.cve() != null
                ? "[" + f.cve() + "|https://nvd.nist.gov/vuln/detail/" + f.cve() + "]" : escape(f.cveText()));
        row(d, "CWE", cwe(f));
        row(d, "Veracode application", escape(f.application()));
        row(d, "Library file", escape(f.fileName()));
        row(d, "Component(s)", escape(String.join(", ", f.components())));
        row(d, "Severity", escape(f.severity()));
        row(d, "CVSS", f.cvss() == null ? "" : f.cvss().toString());
        row(d, "Affected version", escape(f.affectedVersion()));
        row(d, "Location", escape(String.join("; ", f.locations())));
        row(d, "Description", escape(f.description()));
        d.append("\nSource: report ").append(escape(report.fileName()));
        if (report.scanVersion() != null) {
            d.append(", scan ").append(escape(report.scanVersion()));
        }
        if (report.policyName() != null) {
            d.append(", policy ").append(escape(report.policyName()));
        }
        d.append("\n");
        return d.toString();
    }

    /**
     * A SAST issue: the CWE and category, then one row per open flaw (application, severity, file:line, module, Veracode
     * issue id, first found) and the first flaw's description - Veracode words it per CWE, so the flaws share it.
     */
    static String ofSast(SastIssue issue) {
        StringBuilder d = new StringBuilder("h3. Veracode SAST finding\n");
        d.append("||Field||Value||\n");
        row(d, "CWE", issue.cwe() != null && issue.cwe().matches("CWE-\\d+")
                ? "[" + issue.cwe() + "|https://cwe.mitre.org/data/definitions/" + issue.cwe().substring(4) + ".html]"
                : escape(issue.cweText()));
        row(d, "CWE name", escape(issue.cweName()));
        row(d, "Category", escape(issue.categoryText()));
        row(d, "Highest severity", escape(issue.severity()));
        row(d, "Veracode application(s)", escape(String.join(", ", issue.applications())));
        row(d, "Open flaws", String.valueOf(issue.flaws().size()));
        d.append("\n||Application||Severity||File||Line||Module||Issue ID||First found||\n");
        for (SastFlaw f : issue.flaws()) {
            d.append('|').append(cell(f.application())).append('|').append(cell(f.severity()))
                    .append('|').append(cell(f.sourceFile())).append('|').append(f.line() == null ? " " : f.line())
                    .append('|').append(cell(f.module())).append('|').append(cell(f.issueId()))
                    .append('|').append(cell(f.firstFound())).append("|\n");
        }
        String description = issue.flaws().stream().map(SastFlaw::description)
                .filter(t -> t != null && !t.isBlank()).findFirst().orElse(null);
        if (description != null) {
            d.append("\nh4. Description\n").append(escape(description)).append('\n');
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
