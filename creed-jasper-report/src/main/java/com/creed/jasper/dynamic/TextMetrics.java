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
 * {@link ColumnFit} needs and that JasperReports itself never exposes.
 *
 * <p><b>The real face, not an estimate.</b> The font comes from {@link FontUtil#getAwtFontFromBundles}
 * — the same font extension the fill resolves {@code "Creed Sans"} through, and the same
 * locale-restricted family list — so a measurement is what the PDF will actually draw. Estimating
 * from a per-character average would be wrong exactly where it matters: the faces are not
 * metrically compatible, {@code 1013672712} in Noto Sans is not the width of ๑๐๑๓ in Noto Sans
 * Thai, and a column fitted from the wrong number breaks inside a token.
 *
 * <p><b>It can measure nothing, and says so by falling back.</b> A fresh clone that has not
 * provisioned the TTFs from creed-report has no {@code Creed Sans} at all; rather than fail a
 * render over a layout hint, an unresolvable family falls back to the JVM's logical
 * {@code SansSerif} at the same size. The fit is then approximate and the document still renders —
 * which is the same bargain {@code JasperFonts} makes for the fill itself.
 *
 * <p><b>Fonts are cached</b> per family/style/size/locale: resolving one walks the extension's
 * family list and reads a TTF, and a fit measures every cell of every column.
 *
 * <p>Stateless apart from that cache, and safe to call from several fills at once.
 */
public final class TextMetrics {

    /**
     * Antialiased, fractional metrics on — the same two flags JasperReports' own text measurer
     * uses, and they are not cosmetic here: integer metrics round every advance up to a whole
     * point, which over a twelve-character reference is a column two points too wide.
     */
    private static final FontRenderContext CONTEXT = new FontRenderContext(null, true, true);

    private static final Map<String, Font> FONTS = new ConcurrentHashMap<>();

    private TextMetrics() {
    }

    /** The width of {@code text} as one unbroken line. */
    public static float width(String text, CellStyle style, Locale locale) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        return (float) font(style, locale).getStringBounds(text, CONTEXT).getWidth();
    }

    /**
     * The width of the widest line in {@code text} — its <b>maximum</b> content width, i.e. what
     * the column needs for nothing to wrap at all.
     *
     * <p>Only the payload's own {@code \n} breaks a line here (the account block arrives
     * pre-joined); everything else is one line until a column is narrow enough to wrap it.
     */
    public static float maxLineWidth(String text, CellStyle style, Locale locale) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        float widest = 0f;
        for (String line : text.split("\n", -1)) {
            widest = Math.max(widest, width(line, style, locale));
        }
        return widest;
    }

    /**
     * The width of the widest <b>unbreakable</b> run in {@code text} — its minimum content width.
     *
     * <p>This is the number that matters most in this module. A column narrower than its longest
     * token does not wrap, it breaks <i>inside</i> the token: {@code BK260000000} on one line and
     * {@code 1} on the next, which is a reference nobody can read. Keeping every column at or above
     * this width is the whole point of fitting them.
     *
     * <p>Whitespace is the only break opportunity assumed. That is right for Latin and conservative
     * for CJK and Thai, which JasperReports wraps between characters — so their true minimum is
     * narrower than this, and a fit that honours this one simply gives them more room than they
     * need rather than too little.
     */
    public static float minTokenWidth(String text, CellStyle style, Locale locale) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        float widest = 0f;
        for (String token : text.split("\\s+")) {
            widest = Math.max(widest, width(token, style, locale));
        }
        return widest;
    }

    /** The AWT face for a style, out of the same bundles the fill resolves the family through. */
    private static Font font(CellStyle style, Locale locale) {
        int awtStyle = style.bold() ? Font.BOLD : Font.PLAIN;
        String key = style.fontName() + '|' + awtStyle + '|' + style.fontSize() + '|' + locale;
        return FONTS.computeIfAbsent(key, ignored -> resolve(style, awtStyle, locale));
    }

    private static Font resolve(CellStyle style, int awtStyle, Locale locale) {
        // ignoreMissingFont=true: answer null instead of throwing, so a clone without the TTFs
        // gets an approximate fit rather than a failed export.
        Font font = FontUtil.getInstance(DefaultJasperReportsContext.getInstance())
                .getAwtFontFromBundles(style.fontName(), awtStyle, style.fontSize(), locale, true);
        return font != null ? font : new Font(Font.SANS_SERIF, awtStyle, Math.round(style.fontSize()))
                .deriveFont(style.fontSize());
    }
}
