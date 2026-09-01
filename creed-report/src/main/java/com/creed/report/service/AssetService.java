package com.creed.report.service;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;

/**
 * Loads front-end assets once at startup so they can be inlined into the
 * self-contained offline HTML exports (no network / no webjar URLs needed).
 */
@Service
public class AssetService {

    private static final String BOOTSTRAP_VERSION = "5.3.3";
    private static final String ICONS_VERSION = "1.11.3";

    private static final String CSS_PATH = "META-INF/resources/webjars/bootstrap/" + BOOTSTRAP_VERSION + "/css/bootstrap.min.css";
    private static final String JS_PATH  = "META-INF/resources/webjars/bootstrap/" + BOOTSTRAP_VERSION + "/js/bootstrap.bundle.min.js";
    private static final String ICONS_CSS_PATH = "META-INF/resources/webjars/bootstrap-icons/" + ICONS_VERSION + "/font/bootstrap-icons.min.css";
    private static final String ICONS_FONT_PATH = "META-INF/resources/webjars/bootstrap-icons/" + ICONS_VERSION + "/font/fonts/bootstrap-icons.woff2";
    private static final String STYLE_PATH = "static/css/style.css";
    /** Shared, country-neutral look of the report pages; a country's own sheet is layered on top. */
    private static final String REPORT_CSS_PATH = "static/css/report.css";

    private String bootstrapCss;
    private String bootstrapJs;
    private String bootstrapIconsCss;
    private String styleCss;
    private String reportCss;

    @PostConstruct
    void load() throws IOException {
        this.bootstrapCss = readClasspath(CSS_PATH).replace("</style>", "<\\/style>");
        this.bootstrapJs  = readClasspath(JS_PATH).replace("</script>", "<\\/script>");
        this.styleCss = readClasspath(STYLE_PATH).replace("</style>", "<\\/style>");
        this.reportCss = readClasspath(REPORT_CSS_PATH).replace("</style>", "<\\/style>");
        this.bootstrapIconsCss = inlineIconsFont().replace("</style>", "<\\/style>");
    }

    public String bootstrapCss() {
        return bootstrapCss;
    }

    public String bootstrapJs() {
        return bootstrapJs;
    }

    /** Bootstrap-icons CSS with the woff2 font embedded as a data: URI, so icons render offline. */
    public String bootstrapIconsCss() {
        return bootstrapIconsCss;
    }

    /** The project's custom diff/highlight styles (normally served from /css/style.css). */
    public String styleCss() {
        return styleCss;
    }

    /**
     * The report pages' shared stylesheet (normally served from /css/report.css). Country-neutral:
     * the offline export inlines this and then the country's own sheet from
     * {@link com.creed.report.i18n.CountryStyles}.
     */
    public String reportCss() {
        return reportCss;
    }

    private String inlineIconsFont() throws IOException {
        String css = readClasspath(ICONS_CSS_PATH);
        String b64 = Base64.getEncoder().encodeToString(readClasspathBytes(ICONS_FONT_PATH));
        String dataUri = "url(data:font/woff2;base64," + b64 + ")";
        // Replace the relative woff2 reference (which would 404 offline) with the embedded font.
        // The woff fallback url is left untouched but unused once woff2 resolves.
        return css.replaceAll("url\\([\"']?[^)]*bootstrap-icons\\.woff2[^)]*\\)", Matcher.quoteReplacement(dataUri));
    }

    private static String readClasspath(String location) throws IOException {
        return new String(readClasspathBytes(location), StandardCharsets.UTF_8);
    }

    private static byte[] readClasspathBytes(String location) throws IOException {
        ClassPathResource resource = new ClassPathResource(location);
        try (InputStream in = resource.getInputStream()) {
            return StreamUtils.copyToByteArray(in);
        }
    }
}
