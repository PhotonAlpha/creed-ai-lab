package com.creed.report.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the PDF fonts over HTTP, for the browser preview of a PDF template
 * ({@code GET /dynamic/preview/pdf}).
 *
 * <p>{@code classpath:/fonts/} is the set openpdf-html embeds ({@code creed.report.pdf.font-paths}).
 * It is <b>not</b> under {@code static/}, so Boot does not serve it — deliberately, since nothing
 * but the renderer needed it until now. The preview does: without the identical faces the browser
 * substitutes a system font, every line measures differently from the PDF and the preview stops
 * being worth debugging. {@code static/css/report-pdf-preview.css} {@code @font-face}s them under
 * the family names {@code pdf.font.family} asks for.
 *
 * <p>Mapped read-only under its own {@code /fonts/**} prefix rather than by moving the directory
 * into {@code static/}: the font path is configuration ({@code font-paths}), and the renderer must
 * keep reading it from the classpath whether or not this app serves a byte of it.
 */
@Configuration
public class PdfPreviewConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/fonts/**").addResourceLocations("classpath:/fonts/");
    }
}
