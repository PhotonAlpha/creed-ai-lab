package com.creed.resource.veracode.domain;

/**
 * One static-analysis (SAST) flaw, as the report states it: a {@code flaw} under
 * {@code severity/category/cwe/staticflaws}. Open ones are filed in Jira grouped by CWE + category ({@link SastIssue}).
 *
 * @param application       the report's Veracode application ({@code app_name})
 * @param issueId           Veracode's {@code issueid}: stable across scans of one application, not across applications
 * @param severity          the level's name (Very High ... Informational), from the flaw's {@code severity} 0-5
 * @param cwe               {@code CWE-89}; {@code null} without a {@code cweid}
 * @param cweName           the enclosing {@code <cwe cwename>}
 * @param category          the flaw's {@code categoryname}
 * @param sourceFile        {@code sourcefilepath} + {@code sourcefile} ({@code com/creed/report/ReportQueryBuilder.java})
 * @param line              {@code line}; {@code null} when absent or not a number
 * @param remediationStatus {@code remediation_status} as given: New, Open, Reopened, Fixed ...
 * @param mitigationStatus  {@code mitigation_status_desc} (else {@code mitigation_status}): Not Mitigated, Mitigation
 *                          Accepted ...
 * @param mitigated         an approved mitigation ({@code mitigation_status="accepted"})
 * @param fixed             {@code remediation_status="Fixed"}: gone in this scan, listed for the record
 * @param affectsPolicy     {@code affects_policy_compliance}; {@code null} when absent
 * @param firstFound        {@code date_first_occurrence}
 * @param description       the full {@code description}
 */
public record SastFlaw(String application, String issueId, String severity, String cwe, String cweName,
                       String category, String module, String sourceFile, Integer line, String scope,
                       String remediationStatus, String mitigationStatus, boolean mitigated, boolean fixed,
                       Boolean affectsPolicy, String firstFound, String description) {

    /** Neither fixed nor mitigated: what still needs work. */
    public boolean open() {
        return !fixed && !mitigated;
    }
}
