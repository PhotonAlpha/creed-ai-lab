package com.creed.resource.veracode.service;

import java.util.List;

/**
 * A report's SAST statistics, the counterpart of {@link ScaSummary}: what the report states about its static
 * analysis, plus what was counted in its flaws.
 *
 * @param rating            {@code <static-analysis rating>} (A-F); {@code null} without a static analysis
 * @param score             {@code <static-analysis score>}
 * @param totalFlaws        the root's {@code total_flaws}, as Veracode counts them; {@code null} when absent
 * @param flawsNotMitigated the root's {@code flaws_not_mitigated}
 * @param flaws             {@code flaw} elements listed in the report, fixed and mitigated ones included
 * @param open              neither fixed nor mitigated ({@link com.creed.resource.veracode.domain.SastFlaw#open})
 * @param affectsPolicy     open flaws with {@code affects_policy_compliance="true"}
 * @param bySeverity        <em>open</em> flaws per level, Very High first, all six levels listed
 * @param unknownSeverity   open flaws whose severity is none of the six levels
 */
public record SastSummary(String rating, Integer score, Integer totalFlaws, Integer flawsNotMitigated, int flaws,
                          int open, int mitigated, int fixed, int affectsPolicy,
                          List<ScaSummary.SeverityCount> bySeverity, int unknownSeverity) {
}
