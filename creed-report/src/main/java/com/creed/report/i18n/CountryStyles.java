package com.creed.report.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every stylesheet the report renders with, read once at startup so they can be inlined into the
 * outputs that cannot link one: the self-contained offline HTML export, and the PDF (openpdf-html
 * renders from a string with no base URL to resolve a {@code <link>} against). The live page links
 * the browser sheet instead — {@link ReportCountry#styleSheet()} is the same path either way.
 *
 * <p><b>Three layers, one string.</b> A PDF's look is the shared sheet, plus that <i>country</i>'s
 * own rules, plus that <i>locale</i>'s. On the browser side the page can link what it needs, so
 * {@link #browser} returns only the country's half. A PDF cannot link anything, so
 * {@link #pdf(ReportCountry, Locale)} returns all three already concatenated:
 *
 * <pre>
 *   static/css/report-pdf.css                 shared, country- and locale-neutral   (always)
 *   static/css/country/&lt;code&gt;/style-pdf.css   the country edition                   (always, degrades)
 *   static/css/locale/&lt;tag&gt;/report-pdf.css    the locale overlay                    (optional)
 * </pre>
 *
 * Concatenating here rather than handing the templates three variables is deliberate: a caller
 * that forgot one would get a silently half-styled PDF, which is exactly the failure mode this
 * class exists to rule out. It is also why there is no {@code pdf(country)} overload — the locale
 * is not optional at the call site, it is just sometimes an empty layer.
 *
 * <p><b>Why a locale layer at all, when the font stack is already a message key.</b> The stack in
 * {@code pdf.font.family} answers "which face", once, for the whole document. It cannot answer
 * "this caption is bold in Chinese and not in Thai" — a rule, per selector, that a message key has
 * nowhere to live. Noto Sans Thai at 8.5pt fills its loops in when it goes bold, and CJK at the
 * same size reads too light beside Latin unless it does; the same class therefore has to change
 * weight with the script, and that is CSS, not text. The layer goes <b>last</b>, after the country
 * sheet, because what it carries is typography the edition has no business overriding.
 *
 * <p><b>The locale layer is optional and silent about it</b> — unlike the country half, a missing
 * file is the normal case, not a degradation: the base sheet <i>is</i> the right rendering for a
 * locale that needs no adjustment (English, Malay, Vietnamese all ship none). Resolution walks the
 * locale from most to least specific, {@code th-TH} → {@code th} → nothing, so one
 * {@code locale/th/} directory serves every country that renders in Thai.
 *
 * <p>Loading is eager and strict for the two mandatory layers: a country whose stylesheet is
 * missing fails <b>startup</b>, naming the country and the path. That is the point of splitting
 * the per-country CSS into files — the old single shared block could lose a country's rules
 * silently, and a page missing its accent colours is easy to overlook. The country PDF half is the
 * one exception, and not a silent one: {@link ReportCountry#pdfStyleSheet()} hands back
 * {@link ReportCountry#DEFAULT_PDF_STYLESHEET} for a country that ships none, and the countries
 * doing so are named in the startup log.
 *
 * <p>Loaded in the constructor rather than {@code @PostConstruct} so tests and the offline-export
 * renderers can just {@code new CountryStyles()}.
 */
@Component
public class CountryStyles {

    private static final Logger log = LoggerFactory.getLogger(CountryStyles.class);

    /** Shared, country- and locale-neutral PDF rules; the base the other two layers sit on. */
    private static final String PDF_BASE_PATH = "/css/report-pdf.css";

    private final Map<ReportCountry, String> browser;
    private final Map<ReportCountry, String> pdf;
    private final Map<String, String> localePdf;

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
        this.localePdf = Collections.unmodifiableMap(readLocalePdfStyles());
        log.info("Country stylesheets loaded: {}", browser.keySet());
        List<String> defaulted = Arrays.stream(ReportCountry.values())
                .filter(country -> ReportCountry.DEFAULT_PDF_STYLESHEET.equals(country.pdfStyleSheet()))
                .map(ReportCountry::code)
                .toList();
        if (!defaulted.isEmpty()) {
            log.info("Countries with no style-pdf.css of their own, rendering the default edition's"
                    + " {}: {}", ReportCountry.DEFAULT_PDF_STYLESHEET, defaulted);
        }
        log.info("Locale PDF overlays loaded: {} (every other locale renders the base sheet"
                + " unchanged)", localePdf.keySet());
    }

    /** This country's browser stylesheet, ready to inline into a {@code <style>} element. */
    public String browser(ReportCountry country) {
        return browser.get(country);
    }

    /**
     * The complete PDF stylesheet for one country rendered in one locale — the shared
     * {@code report-pdf.css} base, that country's own rules, then that locale's overlay — ready to
     * inline into a PDF template as one {@code ${pdfCss}} block.
     *
     * <p>Pass the <b>same</b> {@code Locale} the template will be rendered in, so the sheet and the
     * message bundle cannot disagree about which language the document is in.
     */
    public String pdf(ReportCountry country, Locale locale) {
        String overlay = localePdf.getOrDefault(localeKey(locale), "");
        return overlay.isEmpty() ? pdf.get(country) : pdf.get(country) + "\n" + overlay;
    }

    /**
     * Which locale overlay a locale resolves to — its own key, or {@code ""} when it renders the
     * base sheet unchanged. Package-private: the tests assert the fallback with it, nothing else
     * needs to know.
     */
    String localeKey(Locale locale) {
        for (String candidate : localeCandidates(locale)) {
            if (localePdf.containsKey(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    /**
     * Every locale overlay that exists, keyed by the tag its directory is named after.
     *
     * <p>The candidate tags come from {@link ReportCountry} — the country × language grid is the
     * complete set of locales this report can be rendered in — so a {@code locale/} directory no
     * country claims is not loaded. That is the same rule the rest of the module follows: adding a
     * language is adding it to a country, never dropping a file in and hoping.
     */
    private static Map<String, String> readLocalePdfStyles() {
        Map<String, String> styles = new LinkedHashMap<>();
        for (String tag : candidateLocaleTags()) {
            ClassPathResource resource = new ClassPathResource("static" + localeStyleSheet(tag));
            if (!resource.exists()) {
                continue;   // The normal case: that locale needs no adjustment to the base sheet.
            }
            try {
                styles.put(tag, readExisting(resource));
            }
            catch (IOException ex) {
                // It is on the classpath and unreadable -- a broken build, not a missing overlay.
                throw new IllegalStateException("Locale overlay classpath:/static"
                        + localeStyleSheet(tag) + " exists but could not be read", ex);
            }
        }
        return styles;
    }

    /** {@code /css/locale/<tag>/report-pdf.css} — the single definition of the overlay's path. */
    static String localeStyleSheet(String tag) {
        return "/css/locale/" + tag + "/report-pdf.css";
    }

    /** Every locale the report can render in, most specific tag first, lower-cased. */
    private static Set<String> candidateLocaleTags() {
        Set<String> tags = new LinkedHashSet<>();
        for (ReportCountry country : ReportCountry.values()) {
            for (String language : country.languages()) {
                Locale effective = CountryProfile.effectiveLocale(country, country.languages(),
                        Locale.forLanguageTag(language));
                tags.addAll(localeCandidates(effective));
            }
        }
        return tags;
    }

    /**
     * A locale's directory names, most specific first: {@code th-TH} → {@code [th-th, th]}. One
     * {@code locale/th/} therefore serves Thailand and any other country that renders in Thai,
     * while a {@code locale/zh-tw/} can still say something {@code locale/zh/} does not.
     */
    private static List<String> localeCandidates(Locale locale) {
        List<String> candidates = new ArrayList<>(2);
        if (locale == null) {
            return candidates;
        }
        String tag = locale.toLanguageTag().toLowerCase(Locale.ROOT);
        String language = locale.getLanguage().toLowerCase(Locale.ROOT);
        if (!tag.isEmpty() && !"und".equals(tag)) {
            candidates.add(tag);
        }
        if (!language.isEmpty() && !candidates.contains(language)) {
            candidates.add(language);
        }
        return candidates;
    }

    /** Reads one stylesheet off the classpath; {@code country} is null for the shared base. */
    private static String read(ReportCountry country, String path) {
        ClassPathResource resource = new ClassPathResource("static" + path);
        try {
            return readExisting(resource);
        }
        catch (IOException ex) {
            throw new IllegalStateException(country == null
                    ? "The shared PDF stylesheet is missing at classpath:/static" + path
                    : "Country " + country.code() + " has no stylesheet at classpath:/static" + path
                            + (ReportCountry.DEFAULT_PDF_STYLESHEET.equals(path)
                                    ? " \u2014 this is the default edition a country without its own"
                                            + " style-pdf.css falls back to, so it must exist"
                                    : " \u2014 every country needs its own directory under static/css/country/"), ex);
        }
    }

    /** Reads a stylesheet whose existence the caller has established. */
    private static String readExisting(ClassPathResource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            // Same guard as AssetService: a stylesheet is inlined into a <style>, so a literal
            // closing tag inside it would end the element early.
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8).replace("</style>", "<\\/style>");
        }
    }
}
