package com.creed.jasper.dynamic;

import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.engine.fonts.FontUtil;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How wide a string is, in points, in the face a {@link CellStyle} asks for — the measurement
 * {@link ColumnFit} needs and that JasperReports never exposes.
 *
 * <p><b>The real face, not an estimate.</b> The font comes from the same extension the fill
 * resolves {@code "Creed Sans"} through ({@link FontUtil#getAwtFontFromBundles}), locale-restricted
 * family list included, so a measurement is what the PDF will draw. A per-character average would
 * be wrong exactly where it matters — the Noto faces are not metrically compatible, and a column
 * fitted from the wrong number breaks inside a token.
 *
 * <p>An unresolvable family falls back to the JVM's {@code SansSerif}: a clone that has not
 * provisioned the TTFs gets an approximate fit rather than a failed export, the same bargain
 * {@code JasperFonts} makes for the fill. Faces are cached — a fit measures every cell of every
 * column, and resolving one walks the family list and reads a TTF.
 */
public final class TextMetrics {

    /**
     * Antialiased with fractional metrics, like JasperReports' own text measurer — not cosmetic:
     * integer metrics round every advance up to a whole point, which over a twelve-character
     * reference is a column two points too wide.
     */
    private static final FontRenderContext CONTEXT = new FontRenderContext(null, true, true);

    private static final Map<String, Font> FONTS = new ConcurrentHashMap<>();

    private TextMetrics() {
    }

    /**
     * The widest line in {@code text} — the column's <b>maximum</b>, i.e. what it needs for nothing
     * to wrap. Only the payload's own {@code \n} breaks a line here.
     */
    public static float maxLineWidth(String text, CellStyle style, Locale locale) {
        return widest(text, "\n", style, locale);
    }

    /**
     * The widest <b>unbreakable</b> run in {@code text} — the column's minimum, and the number that
     * matters most here: narrower than this a cell does not wrap, it breaks <i>inside</i> a token
     * ({@code BK260000000} then {@code 1}), which is a reference nobody can read.
     *
     * <p>Whitespace is the only break opportunity assumed — right for Latin, conservative for CJK
     * and Thai, which JasperReports wraps between characters and which therefore get more room than
     * they need rather than too little.
     */
    public static float minTokenWidth(String text, CellStyle style, Locale locale) {
        return widest(text, "\\s+", style, locale);
    }

    private static float widest(String text, String separator, CellStyle style, Locale locale) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        Font font = font(style, locale);
        float widest = 0f;
        for (String part : text.split(separator, -1)) {
            widest = Math.max(widest, (float) font.getStringBounds(part, CONTEXT).getWidth());
        }
        return widest;
    }

    /** The AWT face for a style, out of the same bundles the fill resolves the family through. */
    private static Font font(CellStyle style, Locale locale) {
        int awtStyle = style.bold() ? Font.BOLD : Font.PLAIN;
        String key = style.fontName() + '|' + awtStyle + '|' + style.fontSize() + '|' + locale;
        return FONTS.computeIfAbsent(key, ignored -> {
            // ignoreMissingFont=true: answer null rather than throw, so a missing TTF costs an
            // approximate fit and not the export.
            Font font = FontUtil.getInstance(DefaultJasperReportsContext.getInstance())
                    .getAwtFontFromBundles(style.fontName(), awtStyle, style.fontSize(), locale, true);
            return font != null ? font
                    : new Font(Font.SANS_SERIF, awtStyle, 1).deriveFont(style.fontSize());
        });
    }
}
