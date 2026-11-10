package com.creed.resource.veracode.service;

import com.creed.resource.veracode.service.VeracodeAnalysisService.UploadedFile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Locale;

/** Report files as a multipart body, one {@code file} part each - how Confluence and Jira both take attachments. */
final class MultipartFiles {

    private MultipartFiles() {
    }

    /** A plain map, not MultipartBodyBuilder: that one needs Reactive Streams, which this servlet app lacks. */
    static MultiValueMap<String, HttpEntity<?>> of(List<UploadedFile> files) {
        MultiValueMap<String, HttpEntity<?>> parts = new LinkedMultiValueMap<>();
        for (UploadedFile f : files) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(mediaType(f.filename()));
            parts.add("file", new HttpEntity<>(new ByteArrayResource(f.content()) {
                @Override
                public String getFilename() {
                    return f.filename();
                }
            }, headers));
        }
        return parts;
    }

    private static MediaType mediaType(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        return name.endsWith(".xml") ? MediaType.APPLICATION_XML
                : name.endsWith(".zip") ? MediaType.parseMediaType("application/zip")
                : MediaType.APPLICATION_OCTET_STREAM;
    }
}
