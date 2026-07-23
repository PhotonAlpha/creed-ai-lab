package com.creed.report.service;

import com.lowagie.text.pdf.BaseFont;
import org.openpdf.pdf.ITextFontResolver;
import org.openpdf.pdf.ITextRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Thymeleaf template -> XHTML -> PDF, rendered with openpdf-html (LibrePDF's Flying Saucer fork on
 * OpenPDF; classes live under {@code org.openpdf.*}).
 *
 * <p>Templates fed through here need print-oriented CSS 2.1 (Flying Saucer's engine is not a
 * browser: no flexbox/grid/JS, so the Bootstrap-based view templates cannot be reused — hence the
 * dedicated {@code *-pdf.html} variants, e.g. {@code report-export-pdf.html}). Markup parsing is lenient —
 * openpdf-html bundles neko-htmlunit, which repairs sloppy HTML instead of failing like classic
 * Flying Saucer's XML parser — but keep the templates well-formed anyway: repair guesses can shift
 * layout. Paged-media CSS ({@code @page} size/margins, margin boxes with
 * {@code counter(page)}/{@code counter(pages)}, {@code page-break-inside}) IS supported and is what
 * the PDF templates use for headers/footers and page numbering.
 *
 * <p><b>Localization.</b> {@link #renderTemplate(String, Map, Locale)} renders the template in the
 * given locale; the PDF templates take all display strings (and their {@code pdf.font.family} CSS
 * font stack) from the {@code messages*.properties} bundles, so a locale switch swaps both the
 * text and the font.
 *
 * <p><b>Fonts.</b> The built-in PDF fonts cover Latin only. {@code creed.report.pdf.font-paths}
 * lists comma-separated resource locations (Spring patterns — {@code classpath:}, {@code file:},
 * wildcards; bare paths are treated as filesystem paths) of font files; each is registered with
 * IDENTITY_H encoding and embedded, addressable from template CSS via its font-family name. By
 * default the bundled {@code classpath:/fonts/} set is loaded: Noto Sans (Latin), Noto Sans SC
 * (Simplified Chinese), Noto Sans TC (Traditional Chinese), Regular + Bold each. Hard-won font
 * constraints: (1) use static glyf-flavored TTFs — with CFF-flavored OTFs (the official noto-cjk
 * builds) OpenPDF embeds the font but silently drops every CJK glyph, and variable TTFs fail to
 * register at all, so the bundled SC/TC files are static instances cut from the variable fonts
 * with fontTools ({@code varLib.instancer wght=NNN --update-name-table}); (2) register a Bold face
 * per family — Flying Saucer does not synthesize bold, and bold-styled CJK text (headings, th)
 * silently disappears if the family only has Regular; (3) no per-glyph fallback across families,
 * so each locale's {@code pdf.font.family} stack leads with the face covering its script.
 *
 * <p>Font loading cost: {@code addFont} takes a file path, a {@code file:}/{@code jar:} URL or a
 * bare classpath-resource path (OpenPDF's {@code RandomAccessFileOrArray} tries disk, then URL
 * schemes, then {@code BaseFont.getResourceStream}), so jar-packaged fonts are read in place —
 * and {@code BaseFont}'s static cache (keyed on name+encoding) parses each font once per JVM, so
 * the per-render cost of re-registering fonts on every renderer is a cache lookup. A renderer is
 * created per call — {@link ITextRenderer} keeps document state (layout, fonts) and is not
 * thread-safe.
 */
@Service
public class PdfExportService {

    private static final Logger log = LoggerFactory.getLogger(PdfExportService.class);

    private final TemplateEngine templateEngine;
    private final ResourcePatternResolver resourceResolver;
    private final String[] fontLocations;
    private volatile List<String> fontFiles;

    public PdfExportService(TemplateEngine templateEngine,
                            ResourceLoader resourceLoader,
                            @Value("${creed.report.pdf.font-paths:classpath:/fonts/*.ttf,classpath:/fonts/*.otf}")
                            String fontPaths) {
        this.templateEngine = templateEngine;
        this.resourceResolver = ResourcePatternUtils.getResourcePatternResolver(resourceLoader);
        this.fontLocations = fontPaths.isBlank() ? new String[0] : fontPaths.split("\\s*,\\s*");
    }

    /**
     * Renders a Thymeleaf template with the given variables in the current request locale
     * (from {@link LocaleContextHolder}) and converts the result to PDF bytes.
     */
    public byte[] renderTemplate(String templateName, Map<String, Object> variables) {
        return renderTemplate(templateName, variables, LocaleContextHolder.getLocale());
    }

    /** Renders a Thymeleaf template in the given locale and converts the result to PDF bytes. */
    public byte[] renderTemplate(String templateName, Map<String, Object> variables, Locale locale) {
        Context context = new Context(locale);
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
        for (String file : fontFiles()) {
            try {
                fontResolver.addFont(file, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            }
            catch (Exception ex) {
                // A missing font degrades that font-family to the built-in defaults; not fatal.
                log.warn("PDF font '{}' could not be registered: {}", file, ex.toString());
            }
        }
    }

    private List<String> fontFiles() {
        List<String> files = this.fontFiles;
        if (files == null) {
            files = resolveFontFiles();
            this.fontFiles = files;
        }
        return files;
    }

    private List<String> resolveFontFiles() {
        List<String> files = new ArrayList<>();
        for (String location : fontLocations) {
            // Bare paths (the pre-resource-pattern config style) are filesystem paths.
            String resolvable = location.contains(":") ? location : "file:" + location;
            try {
                Resource[] resources = resourceResolver.getResources(resolvable);
                if (resources.length == 0) {
                    log.warn("PDF font location '{}' matched no resources", location);
                }
                for (Resource resource : resources) {
                    String font = toFontLocation(resource);
                    if (font != null) {
                        files.add(font);
                    }
                }
            }
            catch (IOException ex) {
                log.warn("PDF font location '{}' could not be resolved: {}", location, ex.toString());
            }
        }
        return files;
    }

    private String toFontLocation(Resource resource) {
        try {
            // Plain files go by path (random-access read); jar-packaged resources go by their
            // jar: URL, which OpenPDF's RandomAccessFileOrArray opens directly.
            return resource.isFile() ? resource.getFile().getAbsolutePath() : resource.getURL().toString();
        }
        catch (IOException ex) {
            log.warn("PDF font resource '{}' could not be resolved: {}", resource, ex.toString());
            return null;
        }
    }
}
