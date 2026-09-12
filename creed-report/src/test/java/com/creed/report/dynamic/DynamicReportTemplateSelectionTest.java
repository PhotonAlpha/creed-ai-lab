package com.creed.report.dynamic;

import com.creed.report.config.MessageSourceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How {@code template=} resolves — the half of the multi-layout feature that has no pixels in it.
 *
 * <p>Two properties matter and neither is visible in a rendered PDF: an absent code must keep
 * printing what this report printed before it had a choice, and an unknown code must be
 * <b>refused</b> rather than defaulted, since a caller who asks for a layout and silently gets a
 * different one has no way to notice.
 */
class DynamicReportTemplateSelectionTest {

    @Test
    void anAbsentTemplateKeepsTheOriginalLayout() {
        // Every caller that predates template= lands here, which is why the default is FORM and
        // not "the newest layout".
        assertThat(DynamicReportTemplate.of(null)).isEqualTo(DynamicReportTemplate.FORM);
        assertThat(DynamicReportTemplate.of("  ")).isEqualTo(DynamicReportTemplate.FORM);
        assertThat(DynamicReportTemplate.from(Map.of())).isEqualTo(DynamicReportTemplate.FORM);
        assertThat(DynamicReportTemplate.DEFAULT).isEqualTo(DynamicReportTemplate.FORM);
    }

    @Test
    void aCodeNamesItsLayoutRegardlessOfCaseOrPadding() {
        assertThat(DynamicReportTemplate.of("statement")).isEqualTo(DynamicReportTemplate.STATEMENT);
        assertThat(DynamicReportTemplate.of(" STATEMENT ")).isEqualTo(DynamicReportTemplate.STATEMENT);
        assertThat(DynamicReportTemplate.from(Map.of("template", "form")))
                .isEqualTo(DynamicReportTemplate.FORM);
    }

    @Test
    void anUnknownTemplateIsBadInputNotAFallback() {
        assertThatThrownBy(() -> DynamicReportTemplate.of("invoice"))
                .isInstanceOf(InvalidTableDefinitionException.class)
                .hasMessageContaining("invoice")
                // The message lists what IS known, so a typo answers itself.
                .hasMessageContaining("form").hasMessageContaining("statement");

        // Same 400 the rest of the definition's bad input gets, not a 500.
        assertThat(InvalidTableDefinitionException.class.getAnnotation(ResponseStatus.class))
                .as("InvalidTableDefinitionException is what makes this a 400").isNotNull();
    }

    @Test
    void theLayoutTravelsWithTheDefinition() {
        // The page's export forms re-post one block of hidden fields; the layout is part of it
        // precisely so the PDF buttons cannot lose the choice made in the picker above them.
        DynamicTableRequest request = DynamicTableRequest.from(
                Map.of("headers", "host", "data", "[]", "template", "statement"));

        assertThat(request.template()).isEqualTo("statement");
        assertThat(request.reportTemplate()).isEqualTo(DynamicReportTemplate.STATEMENT);
        assertThat(new DynamicTableRequest(null, "host", "[]").reportTemplate())
                .isEqualTo(DynamicReportTemplate.FORM);
    }

    @Test
    void everyLayoutNamesItsOwnTemplateAndLabel() {
        // A constant that reused another's template would render the wrong paper under the right
        // name; one that reused a label would be unpickable on the page.
        assertThat(DynamicReportTemplate.values())
                .extracting(DynamicReportTemplate::pdfTemplate)
                .doesNotHaveDuplicates()
                .allSatisfy(template -> assertThat(template).endsWith("-pdf"));
        assertThat(DynamicReportTemplate.values())
                .extracting(DynamicReportTemplate::labelKey)
                .doesNotHaveDuplicates();
    }

    @Test
    void everyLayoutIsNamedInEveryLanguageThePageOffers() {
        // The picker prints #{labelKey}; a constant with no message would show the raw key there.
        // The real bundle chain, so a key added to the wrong file cannot pass here.
        MessageSource messages = new MessageSourceConfig().messageSource();
        for (DynamicReportTemplate template : DynamicReportTemplate.values()) {
            for (String tag : new String[] { "en", "zh-CN", "zh-TW", "th", "ms", "vi" }) {
                Locale locale = Locale.forLanguageTag(tag);
                assertThat(messages.getMessage(template.labelKey(), null, locale))
                        .as("%s label in %s", template.code(), tag)
                        .isNotBlank().isNotEqualTo(template.labelKey());
            }
        }
    }
}
