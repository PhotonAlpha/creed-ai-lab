package com.creed.resource.veracode.service;

/**
 * Which report a request is about: an uploaded file when {@code uploadId} is set, otherwise a Confluence
 * page ({@code pageId}, or the configured default when blank).
 */
public record ReportSourceRef(String pageId, String uploadId) {

    public static ReportSourceRef page(String pageId) {
        return new ReportSourceRef(pageId, null);
    }

    public static ReportSourceRef upload(String uploadId) {
        return new ReportSourceRef(null, uploadId);
    }

    public boolean isUpload() {
        return uploadId != null && !uploadId.isBlank();
    }
}
