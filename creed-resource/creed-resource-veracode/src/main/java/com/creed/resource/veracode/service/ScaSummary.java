package com.creed.resource.veracode.service;

import java.util.List;

/**
 * A report's SCA statistics: what {@code <software_composition_analysis>} states about itself, plus what was
 * counted while reading its {@code vulnerable_components}.
 *
 * <p>The counts are the report's, before {@link com.creed.resource.veracode.domain.Finding#group} merges a CVE
 * listed twice for one jar - so {@code vulnerabilities} can be larger than the page's finding count.
 *
 * @param thirdPartyComponents     {@code third_party_components}: every library SCA saw, vulnerable or not;
 *                                 {@code null} when the report has no SCA section or omits it
 * @param violatesPolicy           {@code violate_policy}
 * @param componentsViolatedPolicy {@code components_violated_policy}
 * @param blacklistedComponents    {@code blacklisted_components}
 * @param vulnerableComponents     {@code component} elements under {@code vulnerable_components}
 * @param vulnerabilities          vulnerabilities read (mitigated ones excluded)
 * @param mitigated                vulnerabilities skipped as {@code mitigation="true"}
 * @param bySeverity               {@code vulnerabilities} per level, Very High first; a level the report never
 *                                 uses is still listed, at 0, so every report has the same columns
 * @param unknownSeverity          vulnerabilities whose severity is none of the six levels
 * @param maxCvss                  the highest CVSS read; {@code null} with none
 */
public record ScaSummary(Integer thirdPartyComponents, Boolean violatesPolicy, Integer componentsViolatedPolicy,
                         Integer blacklistedComponents, int vulnerableComponents, int vulnerabilities, int mitigated,
                         List<SeverityCount> bySeverity, int unknownSeverity, Double maxCvss) {

    /** Veracode's levels, Very High (5) first - the order the page and the workbook list them in. */
    public static final List<String> LEVELS = List.of("Very High", "High", "Medium", "Low", "Very Low",
            "Informational");

    public record SeverityCount(String severity, int count) {
    }
}
