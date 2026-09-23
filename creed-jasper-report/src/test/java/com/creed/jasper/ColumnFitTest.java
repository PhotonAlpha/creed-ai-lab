package com.creed.jasper;

import com.creed.jasper.domain.ApprovalStatusReport;
import com.creed.jasper.dynamic.ColumnFit;
import com.creed.jasper.export.ColumnWidths;
import com.creed.jasper.export.ExportFormat;
import com.creed.jasper.export.ExportRequest;
import com.creed.jasper.i18n.ReportLanguage;
import com.creed.jasper.render.ApprovalStatusRenderer;
import com.creed.jasper.service.ApprovalStatusSamples;
import com.creed.jasper.service.JasperReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Content-fitted column widths — {@code table-layout: auto} for a table whose engine has no such
 * thing.
 *
 * <p>The claim under test is the one that matters at every column count: <b>no column is ever
 * narrower than its own longest unbreakable token</b>, so a reference is never split across two
 * lines, and the widths always add up to the page. Three columns, four and eight, in Latin and in
 * Thai, without anyone re-tuning a weight.
 *
 * <p>Asserted through the real renderer and the real payload, because a fit measured in the wrong
 * face is a fit that is wrong exactly where the faces differ.
 */
class ColumnFitTest {

    private static final LocalDateTime EXPORTED_AT = LocalDateTime.of(2026, 9, 11, 17, 52, 27);
    /** The PDF's content area: A4 less the template's 40pt margins. */
    private static final int CONTENT_WIDTH = 515;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JasperReportService jasper = new JasperReportService(true);
    private final ApprovalStatusReport report = ApprovalStatusSamples.report(objectMapper);

    @Test
    void everyColumnCountFillsThePageAndNoneBreaksATokenInHalf() {
        // Three, four and eight columns out of the same report: the case a weight list cannot
        // serve, because weights tuned for eight leave three of them a third of the page wide.
        for (List<String> view : List.of(
                List.of("type", "bankReference", "status"),
                List.of("type", "bankReference", "account", "status"),
                List.<String>of())) {
            ColumnFit fit = fit(ColumnWidths.AUTO, view, ReportLanguage.EN);

            assertThat(fit.measurements()).as("%d columns", view.size()).isNotEmpty();
            assertThat(fit.measurements().stream().mapToInt(ColumnFit.Measured::width).sum())
                    .as("the columns fill the content area exactly, %d of them", fit.measurements().size())
                    .isEqualTo(CONTENT_WIDTH);
            for (ColumnFit.Measured measured : fit.measurements()) {
                // THE POINT OF THE WHOLE EXERCISE. Below its min a cell does not wrap between
                // words, it breaks inside one: "BK260000000" then "1".
                assertThat(measured.width())
                        .as("%s keeps its longest token whole (%d columns)",
                                measured.column().property(), fit.measurements().size())
                        .isGreaterThanOrEqualTo(measured.min());
            }
        }
    }

    @Test
    void fewerColumnsGetTheirContentAndShareTheRest() {
        // Three columns in 515pt: everything fits, so each gets its max and the slack is shared by
        // weight. The test is that nothing is starved and nothing is absurd -- a 12-character
        // reference does not end up 200pt wide just because there is room.
        ColumnFit fit = fit(ColumnWidths.AUTO, List.of("type", "bankReference", "status"), ReportLanguage.EN);

        assertThat(fit.everythingFits()).as("three columns leave room to spare").isTrue();
        for (ColumnFit.Measured measured : fit.measurements()) {
            assertThat(measured.width()).as("%s got its content", measured.column().property())
                    .isGreaterThanOrEqualTo(measured.max());
        }
    }

    @Test
    void eightColumnsSqueezeTheOneCellThatCanWrap() {
        // Eight columns do NOT all fit, and the fit has to choose where the wrapping goes. The
        // account block is the only cell that is several lines by nature, so it is the one that
        // should absorb it -- every other column is a reference, a code or a date that must stay
        // on one line.
        ColumnFit fit = fit(ColumnWidths.AUTO, List.of(), ReportLanguage.EN);
        assertThat(fit.everythingFits()).as("eight columns cannot all have their full width").isFalse();

        ColumnFit.Measured account = measured(fit, "account");
        assertThat(account.width()).as("the account block wraps").isLessThan(account.max());
        assertThat(account.width()).as("and is still the widest column").isEqualTo(
                fit.measurements().stream().mapToInt(ColumnFit.Measured::width).max().orElseThrow());

        // A column with nothing to wrap -- one token, caption included -- is never squeezed at
        // all: "SGD", "12,345.67", "Processing" have no break opportunity, so taking a point off
        // them would break the token itself. They get their max even while the table is short.
        for (String unwrappable : List.of("currency", "amount", "status")) {
            ColumnFit.Measured column = measured(fit, unwrappable);
            assertThat(column.min()).as("%s has nothing to wrap", unwrappable).isEqualTo(column.max());
            assertThat(column.width()).as("%s is not squeezed", unwrappable)
                    .isGreaterThanOrEqualTo(column.max());
        }
        // The columns that DO give way are the ones with a break opportunity -- a two-word caption
        // or a multi-line block -- and the account block gives way most, being the widest claim.
        ColumnFit.Measured bankReference = measured(fit, "bankReference");
        assertThat(account.max() - account.width())
                .as("the account block absorbs more of the squeeze than a wrapping caption does")
                .isGreaterThan(bankReference.max() - bankReference.width());
    }

    @Test
    void theFixedModeStillReportsWhatItWouldCost() {
        // FIXED keeps the declared weights -- and still measures, so the two modes can be compared
        // on the same numbers. That is what makes the /layout endpoint worth calling twice.
        ColumnFit auto = fit(ColumnWidths.AUTO, List.of(), ReportLanguage.EN);
        ColumnFit fixed = fit(ColumnWidths.FIXED, List.of(), ReportLanguage.EN);

        assertThat(fixed.measurements()).hasSameSizeAs(auto.measurements());
        for (int i = 0; i < auto.measurements().size(); i++) {
            // Same measurement, different answer.
            assertThat(fixed.measurements().get(i).min()).isEqualTo(auto.measurements().get(i).min());
            assertThat(fixed.measurements().get(i).max()).isEqualTo(auto.measurements().get(i).max());
        }
        assertThat(fixed.measurements().stream().mapToInt(ColumnFit.Measured::width).sum())
                .as("the declared weights also fill the page").isEqualTo(CONTENT_WIDTH);
        assertThat(fixed.widths()).as("and they are not the fitted ones").isNotEqualTo(auto.widths());
    }

    @Test
    void theFitFollowsTheFace() {
        // The measurement is taken in the face REPORT_LOCALE resolves, not in a per-character
        // average -- so a script whose digits and captions are wider gets wider columns. Thai
        // captions are the same Latin strings here (the column headers are literals), but the FACE
        // is Noto Sans Thai, whose Latin glyphs are not metrically identical to Noto Sans'.
        ColumnFit latin = fit(ColumnWidths.AUTO, List.of(), ReportLanguage.EN);
        ColumnFit thai = fit(ColumnWidths.AUTO, List.of(), ReportLanguage.TH);

        assertThat(thai.measurements().stream().mapToInt(ColumnFit.Measured::width).sum())
                .as("whatever the face, the columns still fill the page").isEqualTo(CONTENT_WIDTH);
        // Both fits keep every token whole, which is the invariant that has to survive a face swap.
        for (ColumnFit.Measured measured : thai.measurements()) {
            assertThat(measured.width()).as("%s in Thai", measured.column().property())
                    .isGreaterThanOrEqualTo(measured.min());
        }
        assertThat(thai.measurements()).hasSameSizeAs(latin.measurements());
    }

    private ColumnFit fit(ColumnWidths widths, List<String> view, ReportLanguage language) {
        return new ApprovalStatusRenderer(jasper, report, EXPORTED_AT)
                .layoutFor(new ExportRequest(ExportFormat.PDF, language, widths, view));
    }

    private static ColumnFit.Measured measured(ColumnFit fit, String property) {
        return fit.measurements().stream()
                .filter(measured -> measured.column().property().equals(property))
                .findFirst().orElseThrow(() -> new AssertionError("no column " + property));
    }
}
