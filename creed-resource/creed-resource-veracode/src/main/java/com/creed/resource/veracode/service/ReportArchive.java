package com.creed.resource.veracode.service;

import com.creed.resource.veracode.config.UploadProperties;
import com.creed.resource.veracode.service.VeracodeAnalysisService.UploadedFile;
import com.creed.resource.veracode.service.VeracodeXmlParser.UnsupportedFileException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * A {@code .zip} upload as the reports in it: every {@code .xml} entry, at any depth. The rest - the {@code .xsd}
 * Veracode ships next to the report, folders, macOS' {@code __MACOSX/} copies - is skipped. Anything else is
 * passed on unchanged, for the parser to accept or refuse.
 *
 * <p>The archive is a user's file: the limits count bytes actually inflated, not the sizes the entries declare
 * (a zip bomb declares small ones).
 */
@Component
@RequiredArgsConstructor
public class ReportArchive {

    private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};

    private final UploadProperties props;

    public List<UploadedFile> expand(UploadedFile file) {
        if (!isZip(file)) {
            return List.of(file);
        }
        try {
            try {
                return unzip(file, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException | ZipException e) {
                // an entry name that is not UTF-8 ("bad entry name"): a zip made by Windows' "send to compressed
                // folder" in a Chinese locale. A zip that is broken otherwise fails again, and is reported then
                return unzip(file, Charset.forName("GB18030"));
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new UnsupportedFileException(file.filename() + " is not a readable zip: " + e.getMessage());
        }
    }

    static boolean isZip(UploadedFile file) {
        byte[] c = file.content();
        boolean magic = c.length >= 4 && c[0] == ZIP_MAGIC[0] && c[1] == ZIP_MAGIC[1] && c[2] == ZIP_MAGIC[2]
                && c[3] == ZIP_MAGIC[3];
        return magic || file.filename().toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private List<UploadedFile> unzip(UploadedFile archive, Charset names) throws IOException {
        long budget = props.maxUnzippedSize().toBytes();
        List<UploadedFile> reports = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive.content()), names)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                String path = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !isReport(path)) {
                    continue;
                }
                if (reports.size() == props.maxArchiveReports()) {
                    throw new UnsupportedFileException(archive.filename() + " holds more than "
                            + props.maxArchiveReports() + " XML files");
                }
                byte[] content = read(zip, budget, archive.filename());
                budget -= content.length;
                // the entry's path, not just its name: two folders may each hold a detailedreport.xml
                reports.add(new UploadedFile(archive.filename() + "/" + path, content));
            }
        }
        if (reports.isEmpty()) {
            throw new UnsupportedFileException(archive.filename()
                    + " holds no .xml file: zip the Veracode detailed report (XML)");
        }
        return reports;
    }

    private static boolean isReport(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return path.toLowerCase(Locale.ROOT).endsWith(".xml") && !path.startsWith("__MACOSX/") && !name.startsWith(".");
    }

    private byte[] read(InputStream in, long budget, String archive) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) != -1; ) {
            if (out.size() + n > budget) {
                throw new UnsupportedFileException(archive + " unpacks to more than " + size(props.maxUnzippedSize())
                        + " of XML (veracode.upload.max-unzipped-size)");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** {@code 500MB}, {@code 64KB}: DataSize's own toString is in bytes. */
    private static String size(org.springframework.util.unit.DataSize size) {
        long bytes = size.toBytes();
        return bytes % (1024 * 1024) == 0 ? size.toMegabytes() + "MB"
                : bytes % 1024 == 0 ? size.toKilobytes() + "KB" : bytes + "B";
    }
}
