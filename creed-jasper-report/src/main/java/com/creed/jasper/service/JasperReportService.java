package com.creed.jasper.service;

import lombok.extern.slf4j.Slf4j;
import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.export.JRPdfExporter;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput;
import net.sf.jasperreports.export.SimplePdfExporterConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code .jrxml} → {@link JasperReport} → {@link JasperPrint} → PDF bytes: the whole JasperReports
 * pipeline, and the only place in this module that touches the engine.
 *
 * <p><b>The template is the jrxml, not Java.</b> Everything about the document's look — page box,
 * bands, fonts, colours, the conditional styles that change a caption's weight with the locale —
 * lives in {@code classpath:/jasper/*.jrxml}. This class compiles it, hands the fill a data source
 * and a parameter map, and writes PDF; it decides nothing about layout. That is the whole point of
 * the module: creed-report says the same things in CSS.
 *
 * <p><b>Compilation is at runtime and cached.</b> {@code compileReport} runs the report's
 * expressions through the Eclipse compiler, which costs ~100ms per template — once per template per
 * JVM here, keyed on the classpath location. {@code creed.jasper.cache-templates=false} turns the
 * cache off so an edited jrxml takes effect on the next request, the same bargain
 * {@code spring.thymeleaf.cache: false} makes next door. Precompiling to {@code .jasper} at build
 * time is the other option and is deliberately not taken: it puts a build step between the file
 * you edit and the PDF you look at.
 *
 * <p><b>The ecj landmine.</b> That runtime compile is why this module overrides
 * {@code org.eclipse.jdt:ecj}. JasperReports 6.21.3 pins 3.21.0 (2019), which cannot read a modern
 * JDK's class files: on JDK 21+ the first fill dies with {@code Unsupported class file major
 * version}, at request time rather than at startup, and the message names neither JasperReports nor
 * the report. The module pom pins a current ecj instead.
 *
 * <p><b>Fonts are an extension, not code.</b> {@code jasperreports_extension.properties} +
 * {@code fonts/creed-fonts.xml} declare the {@code Creed Sans} families; a jrxml asks for a family
 * by name and the engine picks the face matching {@code REPORT_LOCALE}. See
 * {@link JasperFonts} for what that buys and how it fails.
 */
@Slf4j
@Service
public class JasperReportService {

    private final boolean cacheTemplates;
    private final Map<String, JasperReport> compiled = new ConcurrentHashMap<>();

    public JasperReportService(@Value("${creed.jasper.cache-templates:true}") boolean cacheTemplates) {
        this.cacheTemplates = cacheTemplates;
    }

    /**
     * Compiles a {@code .jrxml} off the classpath, e.g. {@code jasper/approval-status.jrxml}.
     *
     * <p>Public because a <b>subreport</b> is passed into a fill as an already-compiled
     * {@link JasperReport} parameter rather than as a path in the template. A path would be
     * resolved by the engine's repository at fill time — one more thing that can be wrong on a
     * user's download instead of at startup — and it would hide the subreport from anything that
     * wants to compile the module's templates up front.
     */
    public JasperReport compile(String location) {
        if (!cacheTemplates) {
            return compileNow(location);
        }
        return compiled.computeIfAbsent(location, this::compileNow);
    }

    /** Fills a compiled report and exports the result as PDF bytes. */
    public byte[] exportPdf(JasperReport report, Map<String, Object> parameters, JRDataSource dataSource) {
        try {
            JasperPrint print = JasperFillManager.fillReport(report, parameters, dataSource);
            return toPdf(print, report.getName());
        }
        catch (JRException ex) {
            throw new IllegalStateException("Filling " + report.getName() + " failed", ex);
        }
    }

    /** The PDF bytes of an already-filled report — separated so tests can assert on the fill. */
    public byte[] toPdf(JasperPrint print, String documentTitle) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JRPdfExporter exporter = new JRPdfExporter();
            exporter.setExporterInput(new SimpleExporterInput(print));
            exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(out));

            SimplePdfExporterConfiguration configuration = new SimplePdfExporterConfiguration();
            // Named in the reader's title bar and in the file's metadata. A PDF that says
            // "approval-status" where a viewer shows a name is worth the two lines.
            configuration.setMetadataTitle(documentTitle);
            configuration.setMetadataCreator("creed-jasper-report");
            exporter.setConfiguration(configuration);

            exporter.exportReport();
            return out.toByteArray();
        }
        catch (JRException ex) {
            throw new IllegalStateException("PDF export of " + documentTitle + " failed", ex);
        }
    }

    private JasperReport compileNow(String location) {
        ClassPathResource resource = new ClassPathResource(location);
        try (InputStream in = resource.getInputStream()) {
            JasperReport report = JasperCompileManager.compileReport(in);
            log.debug("Compiled Jasper template {}", location);
            return report;
        }
        catch (IOException ex) {
            throw new IllegalStateException("Jasper template classpath:/" + location + " is missing", ex);
        }
        catch (JRException ex) {
            // Everything a jrxml can get wrong -- a bad expression, an unknown font family, an
            // element outside the page box -- surfaces here, at compile time, naming the file.
            throw new IllegalStateException("Jasper template classpath:/" + location
                    + " does not compile", ex);
        }
    }
}
