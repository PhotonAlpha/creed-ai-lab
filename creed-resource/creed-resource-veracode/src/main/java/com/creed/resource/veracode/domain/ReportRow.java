package com.creed.resource.veracode.domain;

/**
 * One finding as the report table states it, one CVE per row (a cell listing two CVEs becomes two rows).
 *
 * @param location  "table 1, row 4" - what a warning or the UI points the reader at
 * @param cve       upper-cased {@code CVE-yyyy-n}, or {@code null} for a flaw without one (SAST, CWE only)
 */
public record ReportRow(String location, String cve, String title, String component, String severity,
                        Double cvss, String affectedVersion, String fixedVersion, String description) {
}
