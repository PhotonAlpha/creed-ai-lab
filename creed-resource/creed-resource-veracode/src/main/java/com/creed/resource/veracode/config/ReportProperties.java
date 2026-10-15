package com.creed.resource.veracode.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Which report column holds which field. Each entry lists accepted header texts, compared
 * case-insensitively after collapsing whitespace — a page edited by hand drifts ("CVE ID", "CVE Id",
 * "Affects Version"), and a renamed header should not need a release.
 *
 * <p>{@code affectedVersion} is the version of <em>our</em> application the scan ran against - the
 * value Jira's "Affects Version/s" holds. The library's own version belongs in {@code component}.
 */
@ConfigurationProperties("veracode.report")
public record ReportProperties(
        @DefaultValue({"CVE", "CVE ID"}) List<String> cve,
        @DefaultValue({"Title", "Vulnerability", "Flaw"}) List<String> title,
        @DefaultValue({"Component", "Library"}) List<String> component,
        @DefaultValue("Severity") List<String> severity,
        @DefaultValue({"CVSS", "CVSS Score"}) List<String> cvss,
        @DefaultValue({"Affected Version", "Affects Version", "Version"}) List<String> affectedVersion,
        @DefaultValue({"Fixed Version", "Fix Version"}) List<String> fixedVersion,
        @DefaultValue({"Description", "Details"}) List<String> description) {
}
