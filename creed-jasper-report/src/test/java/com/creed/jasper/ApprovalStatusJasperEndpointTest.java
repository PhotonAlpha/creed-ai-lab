package com.creed.jasper;

import com.creed.jasper.api.ApprovalStatusJasperController;
import com.creed.jasper.service.ApprovalStatusPdfService;
import com.creed.jasper.service.JasperReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The endpoint itself: standalone MockMvc over the real services, so the whole path from an
 * {@code Accept-Language} header to PDF bytes is exercised without a context or a port.
 */
class ApprovalStatusJasperEndpointTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ApprovalStatusJasperController(new ObjectMapper(),
                    new ApprovalStatusPdfService(new JasperReportService(true))))
            .build();

    @Test
    void itAnswersPdfBytesAsAnAttachment() throws Exception {
        byte[] body = mockMvc.perform(get("/approval-status/export/pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE))
                // ASCII and sortable, never the locale's own date format.
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("creed-approval-status-jasper-")))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(new String(body, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void bothMethodsAnswer() throws Exception {
        // GET and POST alike, the shape creed-report's twin has -- a caller that eventually posts
        // a real payload should not have to change verb as well.
        mockMvc.perform(post("/approval-status/export/pdf")).andExpect(status().isOk());
    }

    @Test
    void theAcceptLanguageHeaderIsTheOnlyThingThatChangesTheDocument() throws Exception {
        byte[] english = render(Locale.ENGLISH);
        byte[] thai = render(Locale.forLanguageTag("th"));
        byte[] unsupported = render(Locale.FRENCH);

        assertThat(thai).as("a different language is a different document").isNotEqualTo(english);
        // Not equal to the English bytes either, because the PDF carries its creation timestamp --
        // what matters is that an unsupported language renders the ENGLISH document rather than
        // whatever the JVM default locale happens to be. Length is the cheap proxy: the Thai
        // edition embeds a second font subset and is substantially larger.
        assertThat(unsupported.length).isCloseTo(english.length, org.assertj.core.data.Offset.offset(2048));
    }

    private byte[] render(Locale locale) throws Exception {
        return mockMvc.perform(get("/approval-status/export/pdf").locale(locale))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }
}
