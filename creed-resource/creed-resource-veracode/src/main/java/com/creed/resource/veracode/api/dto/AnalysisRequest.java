package com.creed.resource.veracode.api.dto;

import com.creed.resource.veracode.service.ReportSourceRef;

/**
 * @param pageId      blank = {@code veracode.confluence.page-id}
 * @param uploadId    re-analyse a file uploaded earlier ({@code POST /analysis/upload}); wins over pageId
 * @param autoCreate  create every missing finding's issue in the same request
 */
public record AnalysisRequest(String pageId, String uploadId, boolean autoCreate) {

    public ReportSourceRef source() {
        return new ReportSourceRef(pageId, uploadId);
    }
}
