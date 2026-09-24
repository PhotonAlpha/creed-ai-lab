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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
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
 * <p><b>Images.</b> {@code setDocumentFromString} is called with no base URL, so a relative or
 * absolute-path {@code src} resolves to nothing — every image has to arrive as a {@code data:} URI.
 * This service therefore reads {@code creed.report.pdf.logo} (and the knockout variant
 * {@code creed.report.pdf.logo-inverse}, for the dark header bar) and {@code creed.report.pdf.stamp}
 * (the seal the statement chrome prints in its footnote) once, base64-encodes them and injects
 * {@code ${logo}} / {@code ${logoInverse}} / {@code ${stamp}} into <b>every</b> template render: it is the
 * single funnel for all PDF output, so a template can rely on the variables existing and no
 * controller can forget to pass them. A missing or unreadable file degrades to an empty string —
 * the templates skip the {@code <img>} — and logs a warning, like a missing font does. Formats are
 * PNG/JPEG/GIF; <b>SVG does not render</b> (Flying Saucer has no SVG support), which is why the
 * bundled logo is a PNG.
 *
 * <p>Four mechanisms for repeating a block on every page were probed; two work, two do not:
 * (1) an {@code @page} margin box cannot hold an image — {@code content: url(...)} silently draws
 * nothing, so margin boxes stay text-only (the page counter);
 * (2) {@code position: fixed} <i>does</i> repeat on every page, but it is positioned against the
 * page's <b>content</b> box and clipped to it, so the negative offsets that would park a logo in
 * the page margin render nothing at all;
 * (3) a wrapper table with {@code -fs-table-paginate: paginate} works — its {@code <thead>} and
 * {@code <tfoot>} repeat on every page and can contain arbitrary markup, images included. That is
 * {@code .page-frame} in the PDF templates. Note the trap: putting a {@code <tfoot>} on the
 * <i>data</i> table instead blew a 60-row table up to 122 pages;
 * (4) {@code position: running(name)} + {@code content: element(name)} in an {@code @page} margin
 * box also works, images included, and is the <b>only</b> way to reach the page <i>margin</i> — so
 * it is the only way to pin a footer to the bottom of a page the content does not fill. The
 * statement chrome's footnote uses it; the frame's {@code <tfoot>} can only reach the foot of the
 * <i>content</i>. Two things it needs: a bottom {@code @page} margin deep enough for the whole
 * block (the box does not grow the margin, it clips), and {@code vertical-align: top} on that box
 * so a float taller than the text beside it grows downward into the band instead of hanging below
 * the page edge. Worth knowing: {@code counter(page)} <b>does</b> resolve inside the running
 * element, because the element is laid out inside a margin box — which is how that footnote carries
 * its own page counter.
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
    private final String logoLocation;
    private final String logoInverseLocation;
    private final String stampLocation;
    private volatile List<String> fontFiles;
    private volatile String logo;
    private volatile String logoInverse;
    private volatile String stamp;

    public PdfExportService(TemplateEngine templateEngine,
                            ResourceLoader resourceLoader,
                            @Value("${creed.report.pdf.font-paths:classpath:/fonts/*.ttf,classpath:/fonts/*.otf}")
                            String fontPaths,
                            @Value("${creed.report.pdf.logo:classpath:/static/img/creed-logo.png}")
                            String logoPath,
                            @Value("${creed.report.pdf.logo-inverse:classpath:/static/img/creed-logo-inverse.png}")
                            String logoInversePath,
                            @Value("${creed.report.pdf.stamp:classpath:/static/img/creed-stamp.png}")
                            String stampPath) {
        this.templateEngine = templateEngine;
        this.resourceResolver = ResourcePatternUtils.getResourcePatternResolver(resourceLoader);
        this.fontLocations = fontPaths.isBlank() ? new String[0] : fontPaths.split("\\s*,\\s*");
        this.logoLocation = logoPath;
        this.logoInverseLocation = logoInversePath;
        this.stampLocation = stampPath;
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
        return renderHtml(renderTemplateHtml(templateName, variables, locale));
    }

    /**
     * The XHTML a PDF is made of — the first half of {@link #renderTemplate}, stopped before the
     * renderer.
     *
     * <p>Exists so a PDF template can be served to a browser <b>as the very string that would have
     * been laid out</b> (see {@code /dynamic/preview/pdf}): same template, same message bundle,
     * same {@code ${pdfCss}}, same injected {@code ${logo}} data URIs. A preview built any other
     * way — a second template, a copy of the model — is a lookalike that can drift, and debugging
     * a lookalike in devtools tells you nothing about the PDF.
     *
     * <p>Note the context is a plain non-web {@link Context}, exactly as the PDF path needs it:
     * the PDF templates must stay renderable without a request, so anything the preview needs from
     * the web layer (the stylesheet URL) arrives as a variable rather than as an {@code @{...}}
     * link expression, which would throw here.
     */
    public String renderTemplateHtml(String templateName, Map<String, Object> variables, Locale locale) {
        Context context = new Context(locale);
        // Set before the caller's variables, so an explicit ${logo} still wins, and set HERE rather
        // than in each controller: this is the one funnel every PDF passes through, which is what
        // makes a logo-less header impossible to ship by forgetting a model attribute.
        context.setVariable("logo", logo());
        context.setVariable("logoInverse", logoInverse());
        context.setVariable("stamp", stamp());
        variables.forEach(context::setVariable);
        return templateEngine.process(templateName, context);
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

    /** The report logo as a {@code data:} URI, or {@code ""} when none could be read. */
    String logo() {
        String uri = this.logo;
        if (uri == null) {
            uri = dataUri(logoLocation);
            this.logo = uri;
        }
        return uri;
    }

    /**
     * The knockout logo for the dark header bar. Degrades to {@link #logo()} when no inverse file
     * is configured or present — the same "the PDF half degrades" rule the country templates follow,
     * so a deployment that only has one logo file still gets a header, just a low-contrast one.
     */
    String logoInverse() {
        String uri = this.logoInverse;
        if (uri == null) {
            String resolved = dataUri(logoInverseLocation);
            uri = resolved.isEmpty() ? logo() : resolved;
            this.logoInverse = uri;
        }
        return uri;
    }

    /**
     * The seal the statement chrome stamps into its footnote, as a {@code data:} URI, or
     * {@code ""} when none could be read. Unlike {@link #logoInverse()} it does <b>not</b> fall
     * back to the logo: a missing seal is a missing seal, and printing the brand mark where a seal
     * belongs would say something the document cannot back up. The template skips the image.
     */
    String stamp() {
        String uri = this.stamp;
        if (uri == null) {
            uri = dataUri(stampLocation);
            this.stamp = uri;
        }
        return uri;
    }

    private String dataUri(String location) {
        if (location == null || location.isBlank()) {
            return "";
        }
        // Bare paths are filesystem paths, as with the font locations.
        String resolvable = location.contains(":") ? location : "file:" + location;
        try {
            Resource resource = resourceResolver.getResource(resolvable);
            if (!resource.exists()) {
                log.warn("PDF image '{}' not found; the PDF header/footer will render without it", location);
                return "";
            }
            String mediaType = imageMediaType(resource.getFilename());
            if (mediaType == null) {
                log.warn("PDF image '{}' is not a PNG/JPEG/GIF; openpdf-html cannot render it "
                        + "(SVG in particular is unsupported)", location);
                return "";
            }
            try (InputStream in = resource.getInputStream()) {
                return "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
            }
        }
        catch (IOException ex) {
            log.warn("PDF image '{}' could not be read: {}", location, ex.toString());
            return "";
        }
    }

    private static String imageMediaType(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        return null;
    }

    /**
     * A {@link BaseFont} for the first family in a CSS font stack — for code that draws on a
     * finished PDF instead of laying one out, i.e. {@link PdfMergeService}'s page counter.
     *
     * <p>Takes the stack the templates already use ({@code pdf.font.family}, e.g.
     * {@code 'Noto Sans Thai', 'Noto Sans', Helvetica, sans-serif}) rather than a second piece of
     * configuration, so a stamped counter is drawn in the same face the page around it was: the
     * leading quoted family is de-spaced and matched against the registered file names
     * ({@code Noto Sans Thai} → {@code NotoSansThai-Regular.ttf}).
     *
     * <p><b>Registered here, not lifted off the page.</b> Flying Saucer embeds a <i>subset</i> —
     * only the glyphs the document drew — so a font taken from a two-page PDF has no "3" to
     * renumber with. This reads the TTF itself, which has every digit.
     *
     * <p>Falls back to Helvetica when no file matches, like every other font failure here: the
     * counter then renders in the built-in face rather than not at all. Latin only, which is fine
     * for digits and wrong for a Thai or CJK separator — a missing font file is already a warning
     * at startup, and this is the same degradation.
     */
    public BaseFont stampingFont(String cssFontStack) {
        String wanted = leadingFamily(cssFontStack);
        if (wanted != null) {
            String needle = wanted.replace(" ", "").toLowerCase(Locale.ROOT);
            for (String file : fontFiles()) {
                String name = file.substring(file.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                // Regular, not Bold: the counter is not bold, and both faces match the family.
                if (name.startsWith(needle + "-regular")) {
                    try {
                        return BaseFont.createFont(file, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                    }
                    catch (Exception ex) {
                        log.warn("Stamping font '{}' could not be loaded: {}", file, ex.toString());
                    }
                }
            }
            log.warn("No registered font file matches family '{}'; stamping in Helvetica", wanted);
        }
        try {
            return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        }
        catch (Exception ex) {
            throw new IllegalStateException("The built-in Helvetica could not be loaded", ex);
        }
    }

    /** {@code 'Noto Sans Thai', 'Noto Sans', sans-serif} → {@code Noto Sans Thai}. */
    private static String leadingFamily(String cssFontStack) {
        if (cssFontStack == null || cssFontStack.isBlank()) {
            return null;
        }
        String first = cssFontStack.split(",")[0].trim();
        if (first.length() > 1 && (first.startsWith("'") || first.startsWith("\""))) {
            first = first.substring(1, first.length() - 1).trim();
        }
        return first.isEmpty() ? null : first;
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
