package com.creed.report.dynamic;

import com.creed.report.config.MessageSourceConfig;
import com.creed.report.i18n.CountryCatalog;
import com.creed.report.i18n.CountryProfile;
import com.creed.report.i18n.CountryProperties;
import com.creed.report.i18n.ReportCountry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The parsing/labelling/formatting rules of the caller-defined table, against the real bundles. */
class DynamicTableServiceTest {

    private final MessageSource messages = new MessageSourceConfig().messageSource();
    private final CountryCatalog catalog = new CountryCatalog(new CountryProperties());
    private final DynamicTableProperties properties = new DynamicTableProperties();
    private final DynamicTableService service =
            new DynamicTableService(new ObjectMapper(), messages, properties);

    @Test
    void headersAreSplitOnCommasAndTranslatedThroughTheColumnBundle() {
        // The payoff of keying columns rather than labelling them: the same headers= renders in
        // whatever language the request asked for, reusing the server report's own column keys.
        assertThat(labels("host,ip,app", ReportCountry.GLOBAL, Locale.ENGLISH))
                .containsExactly("Host", "IP", "App");
        assertThat(labels("host,ip,app", ReportCountry.GLOBAL, Locale.SIMPLIFIED_CHINESE))
                .containsExactly("主机", "IP", "应用");
        assertThat(labels("host,ip,app", ReportCountry.VN, Locale.forLanguageTag("vi")))
                .containsExactly("Máy chủ", "IP", "Ứng dụng");
    }

    @Test
    void anUnknownKeyIsShownVerbatimAndAnExplicitLabelWins() {
        assertThat(labels("host,uptimeDays,cost:Cost (USD)", ReportCountry.GLOBAL, Locale.ENGLISH))
                .containsExactly("Host", "uptimeDays", "Cost (USD)");
    }

    @Test
    void whitespaceAndStrayCommasAreTolerated() {
        assertThat(labels(" host , , ip ,", ReportCountry.GLOBAL, Locale.ENGLISH))
                .containsExactly("Host", "IP");
    }

    @Test
    void objectRowsAreReadByColumnKeyRegardlessOfFieldOrder() {
        DynamicTable table = build("host,ip", "[{\"ip\":\"10.0.0.1\",\"host\":\"a\"}]",
                ReportCountry.GLOBAL, Locale.ENGLISH);
        assertThat(texts(table)).containsExactly(List.of("a", "10.0.0.1"));
    }

    @Test
    void aMissingFieldIsAnEmptyCellRatherThanAFailure() {
        DynamicTable table = build("host,ip,app", "[{\"host\":\"a\"}]", ReportCountry.GLOBAL, Locale.ENGLISH);
        assertThat(texts(table)).containsExactly(List.of("a", "", ""));
    }

    @Test
    void arrayRowsArePositionalAndPaddedOrTruncatedToTheColumns() {
        DynamicTable table = build("host,ip", "[[\"a\",\"10.0.0.1\",\"extra\"],[\"b\"]]",
                ReportCountry.GLOBAL, Locale.ENGLISH);
        assertThat(texts(table)).containsExactly(List.of("a", "10.0.0.1"), List.of("b", ""));
    }

    @Test
    void valuesAreFormattedForTheCountryNotJustTheLanguage() {
        String data = "[{\"n\":1234,\"ok\":true,\"ratio\":1.5,\"note\":null}]";
        // Vietnam groups thousands with dots; the boolean picks up the language's yes/no.
        assertThat(texts(build("n,ok,ratio,note", data, ReportCountry.VN, Locale.forLanguageTag("vi"))))
                .containsExactly(List.of("1.234", "Có", "1,5", ""));
        assertThat(texts(build("n,ok,ratio,note", data, ReportCountry.GLOBAL, Locale.ENGLISH)))
                .containsExactly(List.of("1,234", "Yes", "1.5", ""));
    }

    @Test
    void numbersKeepTheirRawValueForExcelWhileTextIsFormatted() {
        // Excel must still be able to sum a column that arrived as JSON numbers.
        DynamicTable.Cell cell = build("n", "[{\"n\":1234}]", ReportCountry.VN, Locale.ENGLISH)
                .rows().get(0).get(0);
        assertThat(cell.text()).isEqualTo("1.234");
        assertThat(cell.excelValue()).isEqualTo(1234);
    }

    @Test
    void aNestedValueIsShownAsJsonRatherThanJavaToString() {
        DynamicTable table = build("tags", "[{\"tags\":[\"a\",\"b\"]}]", ReportCountry.GLOBAL, Locale.ENGLISH);
        assertThat(texts(table)).containsExactly(List.of("[\"a\",\"b\"]"));
    }

    @Test
    void headersWithoutDataIsAValidEmptyReport() {
        DynamicTable table = build("host,ip", null, ReportCountry.GLOBAL, Locale.ENGLISH);
        assertThat(table.isEmpty()).isTrue();
        assertThat(table.columns()).hasSize(2);
    }

    @Test
    void theTitleFallsBackToTheLocalizedDefault() {
        assertThat(build("host", null, ReportCountry.GLOBAL, Locale.ENGLISH).title())
                .isEqualTo("Dynamic Table Report");
        assertThat(service.build(new DynamicTableRequest("  My report  ", "host", null),
                profile(ReportCountry.GLOBAL, Locale.ENGLISH), Locale.ENGLISH).title())
                .isEqualTo("My report");
    }

    @Test
    void badInputIsRejectedAsBadInput() {
        // All of these are 400s via InvalidTableDefinitionException, never a 500.
        assertThatThrownBy(() -> build(null, "[]", ReportCountry.GLOBAL, Locale.ENGLISH))
                .isInstanceOf(InvalidTableDefinitionException.class).hasMessageContaining("headers");
        assertThatThrownBy(() -> build("host", "{not json", ReportCountry.GLOBAL, Locale.ENGLISH))
                .isInstanceOf(InvalidTableDefinitionException.class).hasMessageContaining("data");
        assertThatThrownBy(() -> build("host", "[\"a scalar row\"]", ReportCountry.GLOBAL, Locale.ENGLISH))
                .isInstanceOf(InvalidTableDefinitionException.class).hasMessageContaining("object or array");
    }

    @Test
    void oversizedPayloadsAreRefusedRatherThanRendered() {
        properties.setMaxColumns(2);
        assertThatThrownBy(() -> build("a,b,c", null, ReportCountry.GLOBAL, Locale.ENGLISH))
                .isInstanceOf(InvalidTableDefinitionException.class).hasMessageContaining("at most 2");

        properties.setMaxColumns(64);
        properties.setMaxRows(1);
        assertThatThrownBy(() -> build("host", "[{},{}]", ReportCountry.GLOBAL, Locale.ENGLISH))
                .isInstanceOf(InvalidTableDefinitionException.class).hasMessageContaining("at most 1");
    }

    private CountryProfile profile(ReportCountry country, Locale language) {
        return catalog.profile(country, language);
    }

    private DynamicTable build(String headers, String data, ReportCountry country, Locale language) {
        CountryProfile profile = profile(country, language);
        return service.build(new DynamicTableRequest(null, headers, data), profile, profile.locale());
    }

    private List<String> labels(String headers, ReportCountry country, Locale language) {
        return build(headers, null, country, language).columns().stream()
                .map(DynamicTable.Column::label).toList();
    }

    private static List<List<String>> texts(DynamicTable table) {
        return table.rows().stream()
                .map(row -> row.stream().map(DynamicTable.Cell::text).toList())
                .toList();
    }
}
