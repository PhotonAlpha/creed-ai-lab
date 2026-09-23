package com.creed.jasper;

import com.creed.jasper.api.ApprovalStatusJasperController;
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
 *
 * <p><b>This class is what makes {@code README.md} true.</b> Every parameter, default, media type,
 * header and error message that file documents is asserted here — the two are meant to be read
 * together, and a change that makes one wrong should fail the other. Anything about how the
 * document is <i>built</i> belongs in the renderer tests; this is the HTTP surface.
 */
class ApprovalStatusJasperEndpointTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ApprovalStatusJasperController(new ObjectMapper(),
                    new JasperReportService(true)))
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
    void theLayoutEndpointAnswersTheColumnPlanForEitherMode() throws Exception {
        // The comparison API: the same measurement, the two answers, without a PDF to squint at.
        String auto = mockMvc.perform(get("/approval-status/layout"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        org.hamcrest.Matchers.containsString(MediaType.APPLICATION_JSON_VALUE)))
                .andReturn().getResponse().getContentAsString();
        String fixed = mockMvc.perform(get("/approval-status/layout").param("widths", "fixed"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(auto).contains("\"widths\":\"auto\"").contains("\"columnCount\":8")
                .contains("\"available\":515").contains("\"bankReference\"")
                // min/max are the measurement, and they are what makes the two modes comparable.
                .contains("\"min\"").contains("\"max\"").contains("\"breaksTokens\"");
        assertThat(fixed).contains("\"widths\":\"fixed\"");
        assertThat(fixed).as("the two modes really do lay the table out differently")
                .isNotEqualTo(auto);
    }

    @Test
    void theLayoutEndpointFollowsTheColumnSubsetAndRefusesAnUnknownMode() throws Exception {
        // Three columns, so the thing the fit exists for is observable through the API: the same
        // report, a different column count, and nobody re-tuned a weight.
        String three = mockMvc.perform(get("/approval-status/layout")
                        .param("columns", "type", "bankReference", "status"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(three).contains("\"columnCount\":3").contains("\"everythingFits\":true");

        // A bad value is a 400 with the reason, like every other bad value here -- never a 500 and
        // never a silent fall back to the default, which would answer for a layout nobody asked for.
        mockMvc.perform(get("/approval-status/layout").param("widths", "elastic"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/approval-status/export").param("widths", "elastic"))
                .andExpect(status().isBadRequest());
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

    @Test
    void theFormatParameterPicksTheFileType() throws Exception {
        assertThat(bytesOf("/approval-status/export?format=csv", MediaType.parseMediaType("text/csv; charset=UTF-8")))
                .startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(bytesOf("/approval-status/export?format=xlsx",
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")))
                .startsWith('P', 'K');
        // The old PDF path stays an alias, so the side-by-side comparison with creed-report's
        // /approval-status/export/pdf keeps working unchanged.
        assertThat(bytesOf("/approval-status/export/pdf", MediaType.APPLICATION_PDF))
                .startsWith('%', 'P', 'D', 'F', '-');
    }

    @Test
    void theFilenameCarriesTheFormatsExtension() throws Exception {
        mockMvc.perform(get("/approval-status/export").param("format", "xlsx"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.endsWith(".xlsx\"")));
    }

    @Test
    void badInputIsFourHundredNotFiveHundred() throws Exception {
        // The two things a caller can get wrong here, and the repo's rule about both.
        mockMvc.perform(get("/approval-status/export").param("format", "pdff"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/approval-status/export").param("columns", "type,nope"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void everyDocumentedFormatAnswersItsOwnMediaType() throws Exception {
        // README: the four formats and the Content-Type each one answers with. HTML is the one
        // with no magic number to check, so the media type IS the assertion.
        assertThat(bytesOf("/approval-status/export?format=pdf", MediaType.APPLICATION_PDF))
                .startsWith('%', 'P', 'D', 'F', '-');
        assertThat(bytesOf("/approval-status/export?format=html",
                MediaType.parseMediaType("text/html; charset=UTF-8"))).isNotEmpty();
        // Case-insensitive, as documented -- a caller that sends PDF should not get a 400.
        mockMvc.perform(get("/approval-status/export").param("format", "PDF"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE));
    }

    @Test
    void theDocumentedDefaultsAreWhatANakedCallGets() throws Exception {
        // README: format defaults to pdf, widths defaults to auto. Asserted where each is
        // observable -- the media type for one, and the layout plan for the other, because two
        // PDFs of the same document differ in their creation timestamp and cannot be compared byte
        // for byte.
        mockMvc.perform(get("/approval-status/export"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE));

        String naked = body(get("/approval-status/layout"));
        String explicit = body(get("/approval-status/layout").param("widths", "auto"));
        assertThat(naked).as("no widths parameter is exactly widths=auto").isEqualTo(explicit);
    }

    @Test
    void theAttachmentIsNamedTheWayTheReadmeSaysItIs() throws Exception {
        // README quotes this header verbatim, timestamp shape included: ASCII and sortable, never
        // the locale's own date format, so a directory of downloads sorts chronologically.
        mockMvc.perform(get("/approval-status/export").header("Accept-Language", "th"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.matchesPattern(
                                ".*filename=\"creed-approval-status-jasper-\\d{8}-\\d{6}\\.pdf\".*")));
    }

    @Test
    void everyBadValueIsAPlainTextFourHundredThatNamesIt() throws Exception {
        // README's error table, line for line. A caller debugging a URL reads this body, so the
        // value it got wrong has to be in it -- "Bad Request" alone is not an answer.
        assertThat(badRequestBody(get("/approval-status/export").param("format", "doc")))
                .contains("doc");
        assertThat(badRequestBody(get("/approval-status/export").param("columns", "type,nope")))
                .contains("nope");
        assertThat(badRequestBody(get("/approval-status/export").param("widths", "elastic")))
                .contains("elastic").contains("auto").contains("fixed");
        assertThat(badRequestBody(get("/approval-status/layout").param("widths", "elastic")))
                .contains("elastic");
    }

    @Test
    void theWidthsParameterReachesTheFormatsThatHaveColumns() throws Exception {
        // README: widths changes the compiled report for every format -- but a CSV has no column
        // widths, so its bytes are the same either way. Both halves are asserted, because the
        // second is the one a caller would otherwise report as a bug.
        byte[] autoCsv = bytesOf("/approval-status/export?format=csv&widths=auto",
                MediaType.parseMediaType("text/csv; charset=UTF-8"));
        byte[] fixedCsv = bytesOf("/approval-status/export?format=csv&widths=fixed",
                MediaType.parseMediaType("text/csv; charset=UTF-8"));
        assertThat(autoCsv).as("a CSV has no column widths to fit").isEqualTo(fixedCsv);

        // The HTML extract does have widths, and carries them in the markup.
        String autoHtml = body(get("/approval-status/export").param("format", "html"));
        String fixedHtml = body(get("/approval-status/export")
                .param("format", "html").param("widths", "fixed"));
        assertThat(autoHtml).as("an extract with columns is laid out by the mode too")
                .isNotEqualTo(fixedHtml);
    }

    @Test
    void theLayoutResponseCarriesEveryFieldTheReadmeDocuments() throws Exception {
        // README documents this JSON field by field; a caller scripts against those names.
        String body = body(get("/approval-status/layout"));
        assertThat(body)
                .contains("\"widths\":").contains("\"language\":\"en\"")
                .contains("\"available\":515").contains("\"total\":515")
                .contains("\"columnCount\":8").contains("\"everythingFits\":")
                .contains("\"property\":").contains("\"header\":").contains("\"weight\":")
                .contains("\"min\":").contains("\"max\":").contains("\"width\":")
                .contains("\"percent\":").contains("\"wraps\":").contains("\"breaksTokens\":");
        // The invariant the README states twice: the fitted widths add up to the content area.
        assertThat(body).contains("\"available\":515").contains("\"total\":515");
        // And the one that matters: nothing in the shipped listing splits a token.
        assertThat(body).as("no column in the default listing breaks a token")
                .doesNotContain("\"breaksTokens\":true");
    }

    @Test
    void theLayoutFollowsTheAcceptLanguageHeaderLikeTheExportDoes() throws Exception {
        // README: the plan is measured in the face the language resolves, so a different language
        // is a different measurement -- which is the whole reason the fit is not a constant table.
        String english = body(get("/approval-status/layout"));
        String thai = body(get("/approval-status/layout").header("Accept-Language", "th"));

        assertThat(thai).contains("\"language\":\"th\"");
        assertThat(thai).as("a different face measures differently").isNotEqualTo(english);
        // Unsupported languages fall back to English here exactly as they do for the document.
        assertThat(body(get("/approval-status/layout").header("Accept-Language", "fr")))
                .contains("\"language\":\"en\"");
    }

    private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String badRequestBody(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain;charset=UTF-8"))
                .andReturn().getResponse().getContentAsString();
    }

    private byte[] bytesOf(String uri, MediaType expected) throws Exception {
        return mockMvc.perform(get(uri))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, expected.toString()))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private byte[] render(Locale locale) throws Exception {
        return mockMvc.perform(get("/approval-status/export/pdf").locale(locale))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }
}
