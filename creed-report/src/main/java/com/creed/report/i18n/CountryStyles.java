package com.creed.report.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Every country's stylesheets, read once at startup so they can be inlined into the outputs that
 * cannot link one: the self-contained offline HTML export, and the PDF (openpdf-html renders from a
 * string with no base URL to resolve a {@code <link>} against). The live page links the browser
 * sheet instead — {@link ReportCountry#styleSheet()} is the same path either way.
 *
 * <p><b>Two layers, one string.</b> A country's look is the shared sheet plus that country's own
 * rules. On the browser side the page can link both, so {@link #browser} returns only the country's
 * half. A PDF cannot link anything, so {@link #pdf} returns the shared {@code report-pdf.css}
 * already concatenated with the country's sheet, country last so it wins at equal specificity.
 * Concatenating here rather than handing the templates a second variable is deliberate: a caller
 * that forgot it would get a silently unstyled PDF, which is exactly the failure mode this class
 * exists to rule out.
 *
 * <p>Loading is eager and strict: a country whose stylesheet is missing fails <b>startup</b>,
 * naming the country and the path. That is the point of splitting the per-country CSS into files —
 * the old single shared block could lose a country's rules silently, and a page missing its accent
 * colours is easy to overlook. The PDF half is the one exception, and not a silent one:
 * {@link ReportCountry#pdfStyleSheet()} hands back {@link ReportCountry#DEFAULT_PDF_STYLESHEET}
 * for a country that ships none, and the countries doing so are named in the startup log.
 *
 * <p>Loaded in the constructor rather than {@code @PostConstruct} so tests and the offline-export
 * renderers can just {@code new CountryStyles()}.
 */
@Component
public class CountryStyles {

    private static final Logger log = LoggerFactory.getLogger(CountryStyles.class);

    /** Shared, country-neutral PDF rules; the base every country's PDF sheet is layered on. */
    private static final String PDF_BASE_PATH = "/css/report-pdf.css";

    private final Map<ReportCountry, String> browser;
    private final Map<ReportCountry, String> pdf;

    public CountryStyles() {
        String pdfBase = read(null, PDF_BASE_PATH);
        Map<ReportCountry, String> browserStyles = new EnumMap<>(ReportCountry.class);
        Map<ReportCountry, String> pdfStyles = new EnumMap<>(ReportCountry.class);
        for (ReportCountry country : ReportCountry.values()) {
            browserStyles.put(country, read(country, country.styleSheet()));
            // Base first: the country's rules are overrides and must come last to win.
            pdfStyles.put(country, pdfBase + "\n" + read(country, country.pdfStyleSheet()));
        }
        this.browser = Collections.unmodifiableMap(browserStyles);
        this.pdf = Collections.unmodifiableMap(pdfStyles);
        log.info("Country stylesheets loaded: {}", browser.keySet());
        List<String> defaulted = Arrays.stream(ReportCountry.values())
                .filter(country -> ReportCountry.DEFAULT_PDF_STYLESHEET.equals(country.pdfStyleSheet()))
                .map(ReportCountry::code)
                .toList();
        if (!defaulted.isEmpty()) {
            log.info("Countries with no style-pdf.css of their own, rendering the default edition's"
                    + " {}: {}", ReportCountry.DEFAULT_PDF_STYLESHEET, defaulted);
        }
    }

    /** This country's browser stylesheet, ready to inline into a {@code <style>} element. */
    public String browser(ReportCountry country) {
        return browser.get(country);
    }

    /**
     * The complete PDF stylesheet for a country — the shared {@code report-pdf.css} base followed
     * by that country's own rules — ready to inline into the PDF template as one block.
     */
    public String pdf(ReportCountry country) {
        return pdf.get(country);
    }

    /** Reads one stylesheet off the classpath; {@code country} is null for the shared base. */
    private static String read(ReportCountry country, String path) {
        ClassPathResource resource = new ClassPathResource("static" + path);
        try (InputStream in = resource.getInputStream()) {
            // Same guard as AssetService: a stylesheet is inlined into a <style>, so a literal
            // closing tag inside it would end the element early.
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8).replace("</style>", "<\\/style>");
        }
        catch (IOException ex) {
            throw new IllegalStateException(country == null
                    ? "The shared PDF stylesheet is missing at classpath:/static" + path
                    : "Country " + country.code() + " has no stylesheet at classpath:/static" + path
                            + (ReportCountry.DEFAULT_PDF_STYLESHEET.equals(path)
                                    ? " — this is the default edition a country without its own"
                                            + " style-pdf.css falls back to, so it must exist"
                                    : " — every country needs its own directory under static/css/country/"), ex);
        }
    }
}
