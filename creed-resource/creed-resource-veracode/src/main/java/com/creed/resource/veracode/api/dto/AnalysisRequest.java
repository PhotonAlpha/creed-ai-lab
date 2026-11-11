package com.creed.resource.veracode.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Re-analyse an uploaded report against Jira as it is now.
 *
 * @param uploadId    from {@code POST /analysis/upload} ({@code report.id})
 * @param autoCreate  create every missing finding's issue in the same request
 * @param fields      create-screen values for {@code autoCreate}: fieldId -> value or values (option id or label)
 * @param jiraVersions the Jira versions to look up in (empty = any); {@code autoCreate} needs exactly one
 * @param countries    the Country options to look up in (empty = any)
 * @param skipSast     only the lookup conditions changed: SAST is not read again ({@code sast} = {@code null})
 */
public record AnalysisRequest(String uploadId, boolean autoCreate, Map<String, Object> fields,
                              List<String> jiraVersions, List<String> countries, boolean skipSast) {
}
