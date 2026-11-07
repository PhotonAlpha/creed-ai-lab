package com.creed.parent.service.report;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.apache.commons.lang3.Strings;
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
@Getter
public class AssetService {

    private static final String BOOTSTRAP_VERSION = "5.3.3";
    private static final String ICONS_VERSION = "1.11.3";

    private static final String CSS_PATH = "META-INF/resources/webjars/bootstrap/" + BOOTSTRAP_VERSION + "/css/bootstrap.min.css";
    private static final String JS_PATH  = "META-INF/resources/webjars/bootstrap/" + BOOTSTRAP_VERSION + "/js/bootstrap.bundle.min.js";
    private static final String ICONS_CSS_PATH = "META-INF/resources/webjars/bootstrap-icons/" + ICONS_VERSION + "/font/bootstrap-icons.min.css";
    private static final String ICONS_FONT_PATH = "META-INF/resources/webjars/bootstrap-icons/" + ICONS_VERSION + "/font/fonts/bootstrap-icons.woff2";
    private static final String STYLE_PATH = "static/pdf/css/style.css";
    private static final String ICON_PATH = "static/favicon.ico";
    private static final String LOGO_IMG_PATH = "static/pdf/img/logo-infinity.svg";

    private String bootstrapCss;
    private String bootstrapJs;
    private String bootstrapIconsCss;
    private String styleCss;
    private String favicon;
    private String logoImg;

    @PostConstruct
    void load() throws IOException {
        this.bootstrapCss = readClasspath(CSS_PATH).replace("</style>", "<\\/style>");
        this.bootstrapJs  = readClasspath(JS_PATH).replace("</script>", "<\\/script>");
        this.styleCss = readClasspath(STYLE_PATH).replace("</style>", "<\\/style>");
        this.bootstrapIconsCss = inlineIconsFont().replace("</style>", "<\\/style>");
        this.favicon = inlineIcons(ICON_PATH);
        this.logoImg = inlineIcons(LOGO_IMG_PATH);
    }


    private String inlineIconsFont() throws IOException {
        String css = readClasspath(ICONS_CSS_PATH);
        String b64 = Base64.getEncoder().encodeToString(readClasspathBytes(ICONS_FONT_PATH));
        String dataUri = "url(data:font/woff2;base64," + b64 + ")";
        // Replace the relative woff2 reference (which would 404 offline) with the embedded font.
        // The woff fallback url is left untouched but unused once woff2 resolves.
        return css.replaceAll("url\\([\"']?[^)]*bootstrap-icons\\.woff2[^)]*\\)", Matcher.quoteReplacement(dataUri));
    }

    private String inlineIcons(String path) throws IOException {
        String b64 = Base64.getEncoder().encodeToString(readClasspathBytes(path));
        if (Strings.CS.endsWith(path, ".svg")) {
            return "data:image/svg+xml;base64," + b64;
        } else {
            return "data:image/x-icon;base64," + b64;
        }
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
