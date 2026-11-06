package com.creed.resource.veracode.service;

import com.creed.resource.veracode.config.UploadProperties;
import com.creed.resource.veracode.service.VeracodeAnalysisService.UploadedFile;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportArchiveTest {

    private static final ReportArchive ARCHIVE = new ReportArchive(
            new UploadProperties(Duration.ofHours(1), 5, DataSize.ofKilobytes(64), 2));

    @Test
    void aPlainFileIsPassedOn() {
        UploadedFile xml = new UploadedFile("report.xml", "<detailedreport/>".getBytes());
        assertThat(ARCHIVE.expand(xml)).containsExactly(xml);
    }

    @Test
    void theLimitsCountInflatedBytes() throws Exception {
        // 1 MB of zeros compresses to about 1 KB: the declared size is not what is checked
        UploadedFile bomb = zip("bomb.zip", Charset.forName("UTF-8"), "report.xml", new byte[1024 * 1024]);
        assertThat(bomb.content().length).isLessThan(16 * 1024);
        assertThatThrownBy(() -> ARCHIVE.expand(bomb)).isInstanceOf(VeracodeXmlParser.UnsupportedFileException.class)
                .hasMessageContaining("unpacks to more than 64KB");

        UploadedFile many = zip("many.zip", Charset.forName("UTF-8"), "a.xml", new byte[1], "b.xml", new byte[1],
                "c.xml", new byte[1]);
        assertThatThrownBy(() -> ARCHIVE.expand(many)).hasMessage("many.zip holds more than 2 XML files");
    }

    @Test
    void entryNamesInTheWindowsCodePageAreRead() throws Exception {
        UploadedFile gbk = zip("报告.zip", Charset.forName("GB18030"), "扫描/报告.xml", "<x/>".getBytes());
        assertThat(ARCHIVE.expand(gbk)).extracting(UploadedFile::filename).containsExactly("报告.zip/扫描/报告.xml");
    }

    private static UploadedFile zip(String name, Charset names, Object... entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, names)) {
            for (int i = 0; i < entries.length; i += 2) {
                zip.putNextEntry(new ZipEntry((String) entries[i]));
                zip.write((byte[]) entries[i + 1]);
                zip.closeEntry();
            }
        }
        return new UploadedFile(name, bytes.toByteArray());
    }
}
