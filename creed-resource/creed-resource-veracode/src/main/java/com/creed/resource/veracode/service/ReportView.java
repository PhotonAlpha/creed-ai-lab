package com.creed.resource.veracode.service;

/**
 * The uploaded Veracode report an analysis is about.
 *
 * @param id               the upload id - what create / export / re-analyse name the report by
 * @param scanVersion      the report's own {@code version} (scan / build name)
 * @param affectedVersion  the version findings are looked up and filed under: {@code scanVersion}, or the
 *                         page's override ({@code versionOverridden})
 */
public record ReportView(String id, String fileName, String appName, String appId, String scanVersion,
                         String affectedVersion, boolean versionOverridden, String policyName,
                         String policyCompliance, String generatedAt) {
}
