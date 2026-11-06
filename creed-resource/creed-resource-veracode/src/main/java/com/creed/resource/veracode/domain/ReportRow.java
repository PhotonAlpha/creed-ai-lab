package com.creed.resource.veracode.domain;

/**
 * One vulnerability of one component, as the report states it.
 *
 * @param location    "log4j-core 2.14.1 (app.war#zip:WEB-INF/lib/log4j-core-2.14.1.jar)" - what a warning or the UI
 *                    points the reader at
 * @param application the report's Veracode application ({@code app_name}); one upload may hold several reports
 * @param cveId       the vulnerability's {@code cve_id} as given, upper-cased: a CVE, or an advisory id such as
 *                    {@code SRCCLR-SID-29587}; {@code null} when the report has none (shown as "Unknown")
 * @param cwe         {@code cwe_id}, e.g. {@code CWE-502}; {@code null} when absent
 * @param fileName    the component's {@code file_name} ({@code log4j-core-2.14.1.jar}): with the CVE, what identifies
 *                    the Jira issue
 * @param summary     the full {@code cve_summary}
 */
public record ReportRow(String location, String application, String cveId, String cwe, String fileName,
                        String component, String severity, Double cvss, String affectedVersion, String summary) {

    public ReportRow withVersion(String version) {
        return new ReportRow(location, application, cveId, cwe, fileName, component, severity, cvss, version,
                summary);
    }
}
