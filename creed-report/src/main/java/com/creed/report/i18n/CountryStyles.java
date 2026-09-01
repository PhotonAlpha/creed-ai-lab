package com.creed.report.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Every country's stylesheets, read once at startup so they can be inlined into the outputs that
 * cannot link one: the self-contained offline HTML export, and the PDF (openpdf-html renders from a
 * string with no base URL to resolve a {@code <link>} against). The live page links the browser
 * sheet instead — {@link ReportCountry#styleSheet()} is the same path either way.
 *
 * <p>Loading is eager and strict: a country whose stylesheet is missing fails <b>startup</b>,
 * naming the country and the path. That is the point of splitting the per-country CSS into files —
 * the old single shared block could lose a country's rules silently, and a page missing its accent
 * colours is easy to overlook.
 *
 * <p>Loaded in the constructor rather than {@code @PostConstruct} so tests and the offline-export
 * renderers can just {@code new CountryStyles()}.
 */
@Component
public class CountryStyles {

    private static final Logger log = LoggerFactory.getLogger(CountryStyles.class);

    private final Map<ReportCountry, String> browser;
    private final Map<ReportCountry, String> pdf;

    public CountryStyles() {
        Map<ReportCountry, String> browserStyles = new EnumMap<>(ReportCountry.class);
        Map<ReportCountry, String> pdfStyles = new EnumMap<>(ReportCountry.class);
        for (ReportCountry country : ReportCountry.values()) {
            browserStyles.put(country, read(country, country.styleSheet()));
            pdfStyles.put(country, read(country, country.pdfStyleSheet()));
        }
        this.browser = Collections.unmodifiableMap(browserStyles);
        this.pdf = Collections.unmodifiableMap(pdfStyles);
        log.info("Country stylesheets loaded: {}", browser.keySet());
    }

    /** This country's browser stylesheet, ready to inline into a {@code <style>} element. */
    public String browser(ReportCountry country) {
        return browser.get(country);
    }

    /** This country's PDF stylesheet, ready to inline into the PDF template. */
    public String pdf(ReportCountry country) {
        return pdf.get(country);
    }

    private static String read(ReportCountry country, String path) {
        ClassPathResource resource = new ClassPathResource("static" + path);
        try (InputStream in = resource.getInputStream()) {
            // Same guard as AssetService: a stylesheet is inlined into a <style>, so a literal
            // closing tag inside it would end the element early.
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8).replace("</style>", "<\\/style>");
        }
        catch (IOException ex) {
            throw new IllegalStateException("Country " + country.code() + " has no stylesheet at classpath:/static"
                    + path + " — every country needs its own directory under static/css/country/", ex);
        }
    }
}
