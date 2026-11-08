package com.creed.resource.veracode.service;

import com.creed.resource.veracode.config.UploadProperties;
import com.creed.resource.veracode.service.VeracodeXmlParser.ParsedReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Parsed uploads by id. Analysis works on the parsed rows, never re-parses. The files as received (an uploaded
 * {@code .xml} / {@code .zip}, or the Confluence attachment) are kept too, gzipped, for one reason: the Confluence
 * export attaches them to the page it creates. A detailed report is tens of MB of XML, which gzips some 20:1 - so
 * {@code max-entries} uploads cost a few MB each rather than the multipart limit each.
 *
 * <p>In memory and per instance - behind a load balancer the follow-up create/export must reach the
 * instance that took the upload, or it answers {@code upload_expired}.
 */
@Component
@RequiredArgsConstructor
public class UploadedReportStore {

    /** One file of an upload; its rows already carry the version shown for them. */
    public record ReportFile(String filename, ParsedReport report, String affectedVersion) {
    }

    /** A file as it was uploaded (or downloaded from Confluence) - a zip stays the zip. */
    public record SourceFile(String filename, byte[] gzipped, int size) {

        static SourceFile of(String filename, byte[] content) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, content.length / 8));
            try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
                gzip.write(content);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new SourceFile(filename, out.toByteArray(), content.length);
        }

        public byte[] content() {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * @param reports one per report (a zip's reports each), in upload order; {@code versionOverridden} = version set
     *                on the page
     * @param files   the files as received, in upload order
     */
    public record Upload(String id, List<ReportFile> reports, List<SourceFile> files, boolean versionOverridden,
                         Instant uploadedAt) {
    }

    private final UploadProperties props;
    private final Clock clock;
    private final Map<String, Upload> uploads = new LinkedHashMap<>();

    /** @param files the files as received, filename to bytes, in upload order */
    public Upload put(List<ReportFile> reports, List<Map.Entry<String, byte[]>> files, boolean overridden) {
        // compressed outside the lock: tens of MB of XML take a moment
        List<SourceFile> kept = files.stream().map(f -> SourceFile.of(f.getKey(), f.getValue())).toList();
        return store(reports, kept, overridden);
    }

    private synchronized Upload store(List<ReportFile> reports, List<SourceFile> files, boolean overridden) {
        evictExpired();
        while (uploads.size() >= props.maxEntries()) {
            Iterator<String> oldest = uploads.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        Upload upload = new Upload(UUID.randomUUID().toString(), List.copyOf(reports), files, overridden, Instant.now(clock));
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
