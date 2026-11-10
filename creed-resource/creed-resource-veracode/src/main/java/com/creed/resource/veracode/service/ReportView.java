package com.creed.resource.veracode.service;

/**
 * One uploaded Veracode report of an analysis; an upload may hold several (one per Veracode application, say).
 *
 * @param scanVersion      the report's own {@code version} (scan / build name)
 * @param affectedVersion  what the page shows as the findings' version: {@code scanVersion}, or the page's override
 *                         ({@code versionOverridden}). Display only - Jira is searched by the page's Jira versions
 * @param sca              the report's SCA statistics
 * @param sast             the report's SAST statistics
 * @param sourceName       the file it came in, as received: the {@code .zip} a report was packed in, or the report
 * @param sourceUrl        the Confluence attachment that file was downloaded from; {@code null} for an upload
 * @param sourcePageTitle  the Confluence page holding that attachment
 * @param sourcePageUrl    that page's URL
 */
public record ReportView(String fileName, String appName, String appId, String scanVersion,
                         String affectedVersion, boolean versionOverridden, String policyName,
                         String policyCompliance, String generatedAt, ScaSummary sca,
                         SastSummary sast, String sourceName, String sourceUrl,
                         String sourcePageTitle, String sourcePageUrl) {
}
