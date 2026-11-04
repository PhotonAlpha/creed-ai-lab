package com.creed.resource.veracode.service;

import com.creed.resource.veracode.domain.Finding;

/** A created issue's description, in Jira wiki markup (the v2 API's format). */
final class JiraDescription {

    private JiraDescription() {
    }

    static String of(Finding f, ReportView report) {
        StringBuilder d = new StringBuilder("h3. Veracode SCA finding\n");
        d.append("||Field||Value||\n");
        if (f.cve() != null) {
            row(d, "CVE", "[" + f.cve() + "|https://nvd.nist.gov/vuln/detail/" + f.cve() + "]");
        }
        row(d, "Title", escape(f.title()));
        row(d, "Component(s)", escape(String.join(", ", f.components())));
        row(d, "Severity", escape(f.severity()));
        row(d, "CVSS", f.cvss() == null ? "" : f.cvss().toString());
        row(d, "Affected version", escape(f.affectedVersion()));
        row(d, "Fixed version", escape(f.fixedVersion()));
        if (f.description() != null) {
            d.append("\n").append(escape(f.description())).append("\n");
        }
        d.append("\nSource: Veracode application ").append(escape(report.appName() == null ? "?" : report.appName()));
        if (report.scanVersion() != null) {
            d.append(", scan ").append(escape(report.scanVersion()));
        }
        if (report.policyName() != null) {
            d.append(", policy ").append(escape(report.policyName()));
        }
        d.append(" (report ").append(escape(report.fileName())).append(") - ")
                .append(escape(String.join("; ", f.locations()))).append("\n");
        return d.toString();
    }

    private static void row(StringBuilder d, String field, String value) {
        d.append('|').append(field).append('|').append(value == null || value.isEmpty() ? " " : value).append("|\n");
    }

    /** {@code |} ends a table cell and {@code [ ] { }} start links/macros in wiki markup. */
    private static String escape(String text) {
        return text == null ? null : text.replaceAll("([|\\[\\]{}])", "\\\\$1");
    }
}
