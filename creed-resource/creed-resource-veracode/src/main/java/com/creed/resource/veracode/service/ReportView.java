package com.creed.resource.veracode.service;

/**
 * One uploaded Veracode report of an analysis; an upload may hold several (one per Veracode application, say).
 *
 * @param scanVersion      the report's own {@code version} (scan / build name)
 * @param affectedVersion  what the page shows as the findings' version: {@code scanVersion}, or the page's override
 *                         ({@code versionOverridden}). Display only - Jira is searched by the page's Jira versions
 * @param sca              the report's SCA statistics
 */
public record ReportView(String fileName, String appName, String appId, String scanVersion,
                         String affectedVersion, boolean versionOverridden, String policyName,
                         String policyCompliance, String generatedAt, ScaSummary sca) {
}
