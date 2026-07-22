package com.creed.report.service;

import com.lowagie.text.pdf.BaseFont;
import org.openpdf.pdf.ITextFontResolver;
import org.openpdf.pdf.ITextRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * Thymeleaf template -> XHTML -> PDF, rendered with openpdf-html (LibrePDF's Flying Saucer fork on
 * OpenPDF; classes live under {@code org.openpdf.*}).
 *
 * <p>Templates fed through here need print-oriented CSS 2.1 (Flying Saucer's engine is not a
 * browser: no flexbox/grid/JS, so the Bootstrap-based view templates cannot be reused — hence the
 * dedicated {@code *-pdf.html} variants, e.g. {@code report-pdf.html}). Markup parsing is lenient —
 * openpdf-html bundles neko-htmlunit, which repairs sloppy HTML instead of failing like classic
 * Flying Saucer's XML parser — but keep the templates well-formed anyway: repair guesses can shift
 * layout. Paged-media CSS ({@code @page} size/margins, margin boxes with
 * {@code counter(page)}/{@code counter(pages)}, {@code page-break-inside}) IS supported and is what
 * the PDF templates use for headers/footers and page numbering.
 *
 * <p>The built-in PDF fonts cover Latin only. For CJK (or any custom) text, point
 * {@code creed.report.pdf.font-paths} at TTF/OTF/TTC files (comma-separated); each is registered
 * with IDENTITY_H encoding and embedded, and becomes addressable from the template CSS via its
 * font-family name. A renderer is created per call — {@link ITextRenderer} keeps document state
 * (layout, fonts) and is not thread-safe.
 */
@Service
public class PdfExportService {

    private static final Logger log = LoggerFactory.getLogger(PdfExportService.class);

    private final TemplateEngine templateEngine;
    private final String[] fontPaths;

    public PdfExportService(TemplateEngine templateEngine,
                            @Value("${creed.report.pdf.font-paths:}") String fontPaths) {
        this.templateEngine = templateEngine;
        this.fontPaths = fontPaths.isBlank() ? new String[0] : fontPaths.split("\\s*,\\s*");
    }

    /** Renders a Thymeleaf template with the given variables and converts the result to PDF bytes. */
    public byte[] renderTemplate(String templateName, Map<String, Object> variables) {
        Context context = new Context();
        variables.forEach(context::setVariable);
        return renderHtml(templateEngine.process(templateName, context));
    }

    /** Converts a self-contained XHTML string to PDF bytes. */
    public byte[] renderHtml(String xhtml) {
        try {
            ITextRenderer renderer = new ITextRenderer();
            registerFonts(renderer.getFontResolver());
            renderer.setDocumentFromString(xhtml);
            renderer.layout();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            renderer.createPDF(out);
            return out.toByteArray();
        }
        catch (Exception ex) {
            throw new IllegalStateException("HTML -> PDF rendering failed", ex);
        }
    }

    private void registerFonts(ITextFontResolver fontResolver) {
        for (String path : fontPaths) {
            try {
                fontResolver.addFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            }
            catch (Exception ex) {
                // A missing font degrades that font-family to the built-in defaults; not fatal.
                log.warn("PDF font '{}' could not be registered: {}", path, ex.toString());
            }
        }
    }
}
