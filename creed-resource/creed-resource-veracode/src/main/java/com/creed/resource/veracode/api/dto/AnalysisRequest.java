package com.creed.resource.veracode.api.dto;

import java.util.Map;

/**
 * Re-analyse an uploaded report against Jira as it is now.
 *
 * @param uploadId    from {@code POST /analysis/upload} ({@code report.id})
 * @param autoCreate  create every missing finding's issue in the same request
 * @param fields      create-screen values for {@code autoCreate}: fieldId -> value or values (option id or label)
 */
public record AnalysisRequest(String uploadId, boolean autoCreate, Map<String, Object> fields) {
}
