package com.creed.resource.veracode.service;

import com.creed.resource.veracode.client.confluence.ConfluenceAttachmentPage;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfluenceServiceTest {

    @Test
    void theTokenGoesOnlyUnderTheBaseUrl() {
        URI cloud = URI.create("https://acme.atlassian.net/wiki");
        assertThat(ConfluenceService.withinBase(URI.create("https://acme.atlassian.net/wiki/download/attachments/1/a.xml"), cloud)).isTrue();
        assertThat(ConfluenceService.withinBase(URI.create("https://ACME.atlassian.net:443/wiki/rest/api/x"), cloud)).isTrue();
        // the media service Cloud redirects downloads to, Jira on the same site, a look-alike path, plain http
        assertThat(ConfluenceService.withinBase(URI.create("https://api.media.atlassian.com/file/x/binary"), cloud)).isFalse();
        assertThat(ConfluenceService.withinBase(URI.create("https://acme.atlassian.net/rest/api/2/issue"), cloud)).isFalse();
        assertThat(ConfluenceService.withinBase(URI.create("https://acme.atlassian.net/wikiX/a"), cloud)).isFalse();
        assertThat(ConfluenceService.withinBase(URI.create("http://acme.atlassian.net/wiki/a"), cloud)).isFalse();
        // Data Center at the root: the whole host
        assertThat(ConfluenceService.withinBase(URI.create("https://wiki.acme.com/download/a"), URI.create("https://wiki.acme.com"))).isTrue();
    }

    @Test
    void anyDownloadLinkUnderTheBaseUrlIsTaken() {
        ConfluenceService service = new ConfluenceService(null, new com.creed.resource.veracode.config.ConfluenceProperties(
                "https://acme.atlassian.net/wiki/", 50, org.springframework.util.unit.DataSize.ofMegabytes(50), 1,
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1)));
        // Cloud now, Data Center / older Cloud
        assertThat(service.downloadUrl(attachment("/rest/api/content/164038/child/attachment/att131236/download")))
                .hasToString("https://acme.atlassian.net/wiki/rest/api/content/164038/child/attachment/att131236/download");
        assertThat(service.downloadUrl(attachment("/download/attachments/1/a%20b.xml?version=2&api=v2")))
                .hasToString("https://acme.atlassian.net/wiki/download/attachments/1/a%20b.xml?version=2&api=v2");
        // out of the base URL, another host, nothing
        for (String link : new String[] {"/../rest/api/2/issue", "//evil.example/x", "https://evil.example/x", " "}) {
            assertThatThrownBy(() -> service.downloadUrl(attachment(link))).as(link).isInstanceOf(UpstreamException.class);
        }
    }

    private static ConfluenceAttachmentPage.Attachment attachment(String download) {
        return new ConfluenceAttachmentPage.Attachment("att1", "r.zip", null, null, null, java.util.Map.of("download", download));
    }

    @Test
    void onlyXmlAndZipCanBeChosen() {
        assertThat(ConfluenceService.supported("report.XML")).isTrue();
        assertThat(ConfluenceService.supported("reports.zip")).isTrue();
        assertThat(ConfluenceService.supported("detailedreport.xsd")).isFalse();
        assertThat(ConfluenceService.supported("summary.pdf")).isFalse();
    }
}
