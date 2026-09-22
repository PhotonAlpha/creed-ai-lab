package com.creed.jasper.service;

import com.creed.jasper.i18n.ReportLanguage;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.engine.fonts.FontFace;
import net.sf.jasperreports.engine.fonts.FontInfo;
import net.sf.jasperreports.engine.fonts.FontUtil;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports, at startup, which embedded face each language actually resolved to.
 *
 * <p>The font extension ({@code jasperreports_extension.properties} +
 * {@code fonts/creed-fonts.xml}) is declarative and is read lazily, on the first fill. That makes
 * its two failure modes late and quiet:
 *
 * <ul>
 *   <li><b>The TTFs are not there.</b> They are creed-report's, packaged into this jar by a shared
 *       resource directory (see the pom). A clone whose {@code creed-report/src/main/resources/fonts}
 *       is empty builds perfectly and then serves a PDF in the built-in Helvetica — blank where
 *       every Thai and CJK glyph should be.</li>
 *   <li><b>The declarations are in the wrong order.</b> A family with no {@code <locales>} supports
 *       every locale, so if the unrestricted "Creed Sans" were declared before the script-specific
 *       ones it would win every lookup and the same glyphs would vanish, with nothing in the
 *       stylesheet or the template to point at.</li>
 * </ul>
 *
 * <p>Both show up here as a line per language naming the resolved face, so a wrong one is visible
 * in the log before anyone downloads anything. {@code JasperFontsTest} asserts the same mapping.
 */
@Slf4j
@Component
public class JasperFonts {

    /** The one family name every template asks for; the locale picks the face behind it. */
    public static final String BODY_FAMILY = "Creed Sans";

    /** The Latin face, never script-resolved — the criteria block's values. */
    public static final String DATA_FAMILY = "Creed Sans Data";

    @PostConstruct
    void logResolvedFaces() {
        log.info("Jasper font families by locale: {}", resolvedFaces());
    }

    /**
     * The normal face each supported language resolves {@link #BODY_FAMILY} to, keyed by locale
     * tag — {@code {en=Noto Sans Regular, th=Noto Sans Thai Regular, ...}}. The value is the
     * face's own name out of the TTF, not the path it was declared with, so it is evidence the
     * file was really parsed rather than merely referenced.
     *
     * <p>A language missing from the result is one whose family did not resolve at all, which is
     * the "fonts were never provisioned" case.
     */
    public Map<String, String> resolvedFaces() {
        Map<String, String> faces = new LinkedHashMap<>();
        FontUtil fontUtil = FontUtil.getInstance(DefaultJasperReportsContext.getInstance());
        for (ReportLanguage language : ReportLanguage.values()) {
            // The same call a fill makes to turn fontName + locale into a face -- so this reports
            // what the engine would actually use, not what the XML was meant to say.
            FontInfo info = fontUtil.getFontInfo(BODY_FAMILY, language.locale());
            if (info != null && info.getFontFamily() != null) {
                faces.put(language.locale().toLanguageTag(), faceName(info.getFontFamily().getNormalFace()));
            }
        }
        return faces;
    }

    /** The face's own name, as the loaded TTF reports it. */
    private static String faceName(FontFace face) {
        String name = face == null ? null : face.getName();
        return name == null ? "<none>" : name;
    }
}
