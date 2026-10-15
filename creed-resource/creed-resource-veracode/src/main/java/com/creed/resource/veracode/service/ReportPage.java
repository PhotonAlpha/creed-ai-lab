package com.creed.resource.veracode.service;

/**
 * Where a report was read from.
 *
 * @param source  {@code CONFLUENCE} (id = page id) or {@code UPLOAD} (id = upload id, title = file name)
 * @param version the page version; {@code null} for an upload
 * @param url     the page's browser URL; {@code null} for an upload
 */
public record ReportPage(String source, String id, String title, Integer version, String url) {

    public static final String CONFLUENCE = "CONFLUENCE";
    public static final String UPLOAD = "UPLOAD";

    public boolean uploaded() {
        return UPLOAD.equals(source);
    }
}
