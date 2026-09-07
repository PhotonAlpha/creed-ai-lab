package com.creed.report.i18n;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The country file layout, and the one place it degrades: a country that ships no PDF fragments or
 * no PDF stylesheet renders the module defaults instead of failing. The browser pair stays
 * mandatory — that is the edition a reader lands on — so nothing here excuses a missing
 * {@code report.html} or {@code style.css}.
 */
class CountryLayoutTest {

    private static final Pattern FRAGMENT = Pattern.compile("th:fragment=\"(\\w+)");

    @Test
    void aCodeNoCountryShipsFilesForFallsBackToTheModuleDefaults() {
        assertThat(ReportCountry.pdfContentTemplateFor("zz")).isEqualTo(ReportCountry.DEFAULT_PDF_TEMPLATE);
        assertThat(ReportCountry.pdfStyleSheetFor("zz")).isEqualTo(ReportCountry.DEFAULT_PDF_STYLESHEET);
    }

    @Test
    void theDefaultsAreActuallyPackaged() {
        // The fallback is only useful if it resolves; a missing default would surface as a
        // template-not-found at export time, i.e. on a user's download.
        assertThat(new ClassPathResource("templates/" + ReportCountry.DEFAULT_PDF_TEMPLATE + ".html").exists())
                .as("default PDF fragments").isTrue();
        assertThat(new ClassPathResource("static" + ReportCountry.DEFAULT_PDF_STYLESHEET).exists())
                .as("default PDF stylesheet").isTrue();
    }

    @Test
    void aCountryThatShipsItsOwnPdfFilesKeepsThem() {
        for (ReportCountry country : ReportCountry.values()) {
            String code = country.code();
            assertThat(country.pdfContentTemplate()).isEqualTo("country/" + code + "/report-pdf");
            assertThat(country.pdfStyleSheet()).isEqualTo("/css/country/" + code + "/style-pdf.css");
        }
    }

    @Test
    void theBrowserPairIsStillResolvedByPathAlone() {
        // No fallback here on purpose: these two are what the live page loads.
        for (ReportCountry country : ReportCountry.values()) {
            assertThat(new ClassPathResource("templates/" + country.contentTemplate() + ".html").exists())
                    .as("browser fragments of %s", country.code()).isTrue();
            assertThat(new ClassPathResource("static" + country.styleSheet()).exists())
                    .as("browser stylesheet of %s", country.code()).isTrue();
        }
    }

    @Test
    void theDefaultFragmentsCoverEveryFragmentACountryDefines() {
        // A country's file replaces the default wholesale, so the default must define at least the
        // same fragment names — otherwise falling back would 500 on the fragment the PDF asks for.
        Set<String> defaults = fragmentsOf("templates/" + ReportCountry.DEFAULT_PDF_TEMPLATE + ".html");
        assertThat(defaults).contains("notice");

        for (ReportCountry country : ReportCountry.values()) {
            if (country.pdfContentTemplate().equals(ReportCountry.DEFAULT_PDF_TEMPLATE)) {
                continue;
            }
            assertThat(defaults)
                    .as("fragments %s defines that the default does not", country.code())
                    .containsAll(fragmentsOf("templates/" + country.pdfContentTemplate() + ".html"));
        }
    }

    private static Set<String> fragmentsOf(String classpathLocation) {
        try (InputStream in = new ClassPathResource(classpathLocation).getInputStream()) {
            Matcher matcher = FRAGMENT.matcher(StreamUtils.copyToString(in, StandardCharsets.UTF_8));
            Set<String> names = new LinkedHashSet<>();
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
            return names;
        }
        catch (IOException ex) {
            throw new IllegalStateException("Cannot read " + classpathLocation, ex);
        }
    }
}
