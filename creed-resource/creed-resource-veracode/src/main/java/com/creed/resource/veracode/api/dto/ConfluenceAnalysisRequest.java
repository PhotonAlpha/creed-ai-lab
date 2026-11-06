package com.creed.resource.veracode.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Analyse attachments of a Confluence page, downloaded by the server: as an upload of the same files would be.
 *
 * @param pageId          the page they are on
 * @param attachmentIds   from {@code GET /confluence/pages/{pageId}/attachments}; {@code .xml} or {@code .zip}
 * @param affectedVersion shown instead of each report's {@code version} (display only); blank = the report's
 * @param autoCreate      create every missing finding's issue in the same request
 * @param fields          create-screen values for {@code autoCreate}
 * @param jiraVersions    the Jira versions to look up in (empty = any); {@code autoCreate} needs exactly one
 */
public record ConfluenceAnalysisRequest(String pageId, List<String> attachmentIds, String affectedVersion,
                                        boolean autoCreate, Map<String, Object> fields, List<String> jiraVersions) {
}
