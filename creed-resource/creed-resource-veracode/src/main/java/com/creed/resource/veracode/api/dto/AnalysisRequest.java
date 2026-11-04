package com.creed.resource.veracode.api.dto;

/**
 * Re-analyse an uploaded report against Jira as it is now.
 *
 * @param uploadId    from {@code POST /analysis/upload} ({@code report.id})
 * @param autoCreate  create every missing finding's issue in the same request
 */
public record AnalysisRequest(String uploadId, boolean autoCreate) {
}
