package com.creed.resource.veracode.service;

import com.creed.resource.veracode.config.UploadProperties;
import com.creed.resource.veracode.service.ReportParser.ParsedReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Parsed uploads by id. The parsed rows are kept, not the file: re-parsing would give the same rows, and
 * the bytes of a 10 MB workbook are not worth holding.
 *
 * <p>In memory and per instance - behind a load balancer the follow-up create/export must reach the
 * instance that took the upload, or it answers {@code upload_expired}.
 */
@Component
@RequiredArgsConstructor
public class UploadedReportStore {

    public record Upload(String id, String filename, ParsedReport report, Instant uploadedAt) {
    }

    private final UploadProperties props;
    private final Clock clock;
    private final Map<String, Upload> uploads = new LinkedHashMap<>();

    public synchronized Upload put(String filename, ParsedReport report) {
        evictExpired();
        while (uploads.size() >= props.maxEntries()) {
            Iterator<String> oldest = uploads.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        Upload upload = new Upload(UUID.randomUUID().toString(), filename, report, Instant.now(clock));
        uploads.put(upload.id(), upload);
        return upload;
    }

    public synchronized Upload get(String id) {
        evictExpired();
        Upload upload = uploads.get(id);
        if (upload == null) {
            throw new UploadNotFoundException(id);
        }
        return upload;
    }

    private void evictExpired() {
        Instant cutoff = Instant.now(clock).minus(props.ttl());
        uploads.values().removeIf(u -> u.uploadedAt().isBefore(cutoff));
    }

    public static class UploadNotFoundException extends RuntimeException {
        public UploadNotFoundException(String id) {
            super("upload " + id + " is no longer held by this service (expired or restarted) - upload the file again");
        }
    }
}
