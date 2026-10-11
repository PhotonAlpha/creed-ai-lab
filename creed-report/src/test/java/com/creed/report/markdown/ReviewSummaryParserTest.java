package com.creed.report.markdown;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReviewSummaryParserTest {

    private final ReviewSummaryParser parser = new ReviewSummaryParser();

    /** The document in docs/preview.webp (its Reason line is cut off there; the ending is made up). */
    private static String fixture() throws IOException {
        try (InputStream in = ReviewSummaryParserTest.class.getResourceAsStream("/markdown/pr-review.md")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void readsTheVerdictOfThePreviewDocument() throws IOException {
        Verdict verdict = parser.verdict(fixture());
        assertThat(verdict.raw()).isEqualTo("🟡 Approve with comments");
        assertThat(verdict.label()).isEqualTo("Approve with comments");
        assertThat(verdict.indicator()).isEqualTo("🟡");
    }

    @Test
    void readsEveryRowInOrderUnderItsHeading() throws IOException {
        ReviewSummary summary = parser.parse(fixture());
        assertThat(summary.heading()).isEqualTo("1. Manager Summary");
        assertThat(summary.items().keySet()).containsExactly(
                "Verdict", "Risk", "Score", "Areas touched", "Test impact", "Issues", "Reason");
        assertThat(summary.get("risk")).contains("Medium");
        assertThat(summary.get(" Score ")).contains("8.5/10");
        assertThat(summary.get("Issues")).contains("0 Blocker, 1 Major, 0 Minor");
        assertThat(summary.get("Item")).isEmpty(); // the header row is not an item
    }

    @Test
    void cellMarkupComesOutAsText() {
        String md = """
                ## Manager Summary

                Item | Result
                :--- | ---
                **Verdict** | ✅ **Approve** — see [notes](https://example.invalid) \\| `ok`
                """;
        assertThat(parser.verdict(md).raw()).isEqualTo("✅ Approve — see notes | ok");
        assertThat(parser.verdict(md).indicator()).isEqualTo("✅");
    }

    @Test
    void theSummarySectionWinsOverAnEarlierTableAndCodeFencesAreIgnored() {
        String md = """
                # Changes

                | Item | Result |
                |---|---|
                | Verdict | 🔴 Request changes |

                ```markdown
                | Verdict | ⚪ from a code sample |
                ```

                # 1. Manager Summary

                | Item | Result |
                |---|---|
                | Verdict | 🟢 Approve |
                """;
        assertThat(parser.verdict(md).raw()).isEqualTo("🟢 Approve");
    }

    @Test
    void withoutTheHeadingTheFirstTableWithAVerdictRowIsUsed() {
        String md = """
                | File | Lines |
                |---|---|
                | a.yml | 12 |

                | Item | Result |
                |---|---|
                | Verdict | Approve with comments |
                """;
        Verdict verdict = parser.verdict(md);
        assertThat(verdict.label()).isEqualTo("Approve with comments");
        assertThat(verdict.indicator()).isNull();
        assertThat(parser.parse(md).heading()).isNull();
    }

    @Test
    void shortcodeAndVariationSelectorIndicators() {
        assertThat(Verdict.of(":yellow_circle: Approve with comments"))
                .isEqualTo(new Verdict(":yellow_circle: Approve with comments", "Approve with comments", ":yellow_circle:"));
        assertThat(Verdict.of("⚠️ Needs work").indicator()).isEqualTo("⚠️");
        assertThat(Verdict.of("⚠️ Needs work").label()).isEqualTo("Needs work");
    }

    @Test
    void missingTableOrRowIs400() {
        assertThatThrownBy(() -> parser.verdict("# Manager Summary\n\nNo table here."))
                .isInstanceOf(ReviewSummaryNotFoundException.class).hasMessageContaining("no summary table");
        assertThatThrownBy(() -> parser.verdict("# Manager Summary\n\n| Item | Result |\n|---|---|\n| Risk | Low |\n"))
                .hasMessageContaining("under 'Manager Summary' has no Verdict row");
    }

    @Test
    void endpointDecodesUtf8WithoutACharsetAndAnswersJson() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MarkdownSummaryController(parser)).build();
        byte[] body = fixture().getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/api/markdown/verdict").contentType("text/markdown").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.raw").value("🟡 Approve with comments"))
                .andExpect(jsonPath("$.label").value("Approve with comments"))
                .andExpect(jsonPath("$.indicator").value("🟡"));
        mvc.perform(post("/api/markdown/summary").contentType(MediaType.TEXT_PLAIN).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heading").value("1. Manager Summary"))
                .andExpect(jsonPath("$.items.Verdict").value("🟡 Approve with comments"))
                .andExpect(jsonPath("$.items['Test impact']").value("Required"));
        mvc.perform(post("/api/markdown/verdict").contentType("text/markdown").content("no table"))
                .andExpect(status().isBadRequest());
    }
}
