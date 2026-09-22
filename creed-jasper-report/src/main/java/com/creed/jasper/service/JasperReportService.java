package com.creed.jasper.service;

import com.creed.jasper.dynamic.ReportShape;
import com.creed.jasper.dynamic.TableDesigner;
import com.creed.jasper.export.ExportFormat;
import lombok.extern.slf4j.Slf4j;
import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.design.JRDesignQuery;
import net.sf.jasperreports.engine.design.JasperDesign;
import net.sf.jasperreports.engine.export.HtmlExporter;
import net.sf.jasperreports.engine.export.JRCsvExporter;
import net.sf.jasperreports.engine.export.JRPdfExporter;
import net.sf.jasperreports.engine.export.ooxml.JRXlsxExporter;
import net.sf.jasperreports.engine.xml.JRXmlLoader;
import net.sf.jasperreports.export.Exporter;
import net.sf.jasperreports.export.SimpleHtmlExporterOutput;
import net.sf.jasperreports.export.SimpleWriterExporterOutput;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput;
import net.sf.jasperreports.export.SimpleCsvExporterConfiguration;
import net.sf.jasperreports.export.SimplePdfExporterConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    private final Map<ReportShape, JasperReport> compiled = new ConcurrentHashMap<>();

    public JasperReportService(@Value("${creed.jasper.cache-templates:true}") boolean cacheTemplates) {
        this.cacheTemplates = cacheTemplates;
    }

    /**
     * Compiles a {@code .jrxml} off the classpath, e.g. {@code jasper/approval-status.jrxml},
     * exactly as written.
     *
     * <p>Public because a <b>subreport</b> is passed into a fill as an already-compiled
     * {@link JasperReport} parameter rather than as a path in the template. A path would be
     * resolved by the engine's repository at fill time — one more thing that can be wrong on a
     * user's download instead of at startup — and it would hide the subreport from anything that
     * wants to compile the module's templates up front.
     */
    public JasperReport compile(String location) {
        return compile(ReportShape.of(location));
    }

    /**
     * Compiles a {@link ReportShape}: a {@code .jrxml} with its table <b>generated</b> into it,
     * optionally stripped of its chrome, optionally fed from JSON.
     *
     * <p>This is the DynamicJasper-shaped call, without DynamicJasper — see {@link TableDesigner}
     * for what that buys and what it costs. The template it is given declares no table bands and
     * no fields; both come from the shape's column list, so a caller can change the shape of the
     * listing, or ask for the table without the document around it, without touching a file.
     *
     * <p>The whole shape is the cache key, not just the location: two column lists, or a
     * chrome/no-chrome pair, are two different compiled reports.
     */
    public JasperReport compile(ReportShape shape) {
        if (!cacheTemplates) {
            return compileNow(shape);
        }
        return compiled.computeIfAbsent(shape, this::compileNow);
    }

    /** Fills a compiled report and exports the result in the given format. */
    public byte[] export(JasperReport report, Map<String, Object> parameters, JRDataSource dataSource,
                         ExportFormat format) {
        try {
            // A null data source is not an oversight: a JSON-fed shape carries a query, and the
            // engine builds the source itself from JSON_INPUT_STREAM.
            JasperPrint print = dataSource == null
                    ? JasperFillManager.fillReport(report, parameters)
                    : JasperFillManager.fillReport(report, parameters, dataSource);
            return export(print, report.getName(), format);
        }
        catch (JRException ex) {
            throw new IllegalStateException("Filling " + report.getName() + " failed", ex);
        }
    }

    /** Fills a compiled report and exports it as PDF — the common case, spelled out. */
    public byte[] exportPdf(JasperReport report, Map<String, Object> parameters, JRDataSource dataSource) {
        return export(report, parameters, dataSource, ExportFormat.PDF);
    }

    /**
     * The bytes of an already-filled report, in one of the {@link ExportFormat}s — separated from
     * the fill so a test can assert on the {@link JasperPrint} and so one fill can be written out
     * more than once.
     *
     * <p>The migrated form of the reference implementation's {@code jasperService.exportFile}. Two
     * of its details are worth keeping and are easy to lose:
     *
     * <ul>
     *   <li><b>the CSV byte-order mark</b>. Without it Excel reads a UTF-8 CSV as the platform
     *       encoding and every non-Latin caption arrives as mojibake — the export is not wrong, it
     *       just cannot be opened by the tool people open CSVs with;</li>
     *   <li><b>the writer-based output</b> for CSV and HTML. A {@code SimpleWriterExporterOutput}
     *       with an explicit UTF-8 encoding, not a raw stream, or the Thai and CJK text is written
     *       in the JVM's default charset.</li>
     * </ul>
     */
    public byte[] export(JasperPrint print, String documentTitle, ExportFormat format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // Configured whole in each factory rather than here: Exporter's four type
            // parameters mean a wildcard-typed one will not accept an input, so the generic hand
            // that looks natural does not compile.
            Exporter<?, ?, ?, ?> exporter = switch (format) {
                case PDF -> pdfExporter(print, out, documentTitle);
                case XLSX -> xlsxExporter(print, out);
                case CSV -> csvExporter(print, out);
                case HTML -> htmlExporter(print, out);
            };
            exporter.exportReport();
            return out.toByteArray();
        }
        catch (JRException ex) {
            throw new IllegalStateException(format.code() + " export of " + documentTitle + " failed", ex);
        }
    }

    /** The PDF bytes of an already-filled report. */
    public byte[] toPdf(JasperPrint print, String documentTitle) {
        return export(print, documentTitle, ExportFormat.PDF);
    }

    private static JRPdfExporter pdfExporter(JasperPrint print, ByteArrayOutputStream out,
                                             String documentTitle) {
        JRPdfExporter exporter = new JRPdfExporter();
        exporter.setExporterInput(new SimpleExporterInput(print));
        exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(out));

        SimplePdfExporterConfiguration configuration = new SimplePdfExporterConfiguration();
        // Named in the reader's title bar and in the file's metadata. A PDF that says
        // "approval-status" where a viewer shows a name is worth the two lines.
        configuration.setMetadataTitle(documentTitle);
        configuration.setMetadataCreator("creed-jasper-report");
        exporter.setConfiguration(configuration);
        return exporter;
    }

    private static JRXlsxExporter xlsxExporter(JasperPrint print, ByteArrayOutputStream out) {
        // JasperReports writes the OOXML itself, so this needs no POI on the classpath -- unlike
        // the legacy .xls exporter, which does.
        JRXlsxExporter exporter = new JRXlsxExporter();
        exporter.setExporterInput(new SimpleExporterInput(print));
        exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(out));
        return exporter;
    }

    private static JRCsvExporter csvExporter(JasperPrint print, ByteArrayOutputStream out) {
        JRCsvExporter exporter = new JRCsvExporter();
        exporter.setExporterInput(new SimpleExporterInput(print));
        SimpleWriterExporterOutput output = new SimpleWriterExporterOutput(out, StandardCharsets.UTF_8.name());
        exporter.setExporterOutput(output);
        SimpleCsvExporterConfiguration configuration = new SimpleCsvExporterConfiguration();
        // So Excel opens a UTF-8 CSV as UTF-8. Dropping this is how a Thai export becomes mojibake
        // in the one program people actually open CSVs with.
        configuration.setWriteBOM(Boolean.TRUE);
        exporter.setConfiguration(configuration);
        return exporter;
    }

    private static HtmlExporter htmlExporter(JasperPrint print, ByteArrayOutputStream out) {
        HtmlExporter exporter = new HtmlExporter();
        exporter.setExporterInput(new SimpleExporterInput(print));
        exporter.setExporterOutput(new SimpleHtmlExporterOutput(out, StandardCharsets.UTF_8.name()));
        return exporter;
    }

    private JasperReport compileNow(ReportShape shape) {
        String location = shape.templateLocation();
        ClassPathResource resource = new ClassPathResource(location);
        try (InputStream in = resource.getInputStream()) {
            // Loaded as a DESIGN rather than compiled straight from the stream, so the table can be
            // written into it before the expressions are compiled. A design is mutable; a
            // JasperReport is not, which is why the generation has to happen here and not later.
            JasperDesign design = JRXmlLoader.load(in);
            if (!shape.chrome()) {
                TableDesigner.stripChrome(design);
            }
            if (shape.columns() != null) {
                TableDesigner.write(design, shape.layout(), shape.columns());
            }
            if (shape.jsonQuery() != null) {
                // A query turns the fill around: instead of being handed a JRDataSource the engine
                // builds one from JsonQueryExecuterFactory.JSON_INPUT_STREAM. The text is the
                // JSONPath of the array the rows live in.
                JRDesignQuery query = new JRDesignQuery();
                query.setLanguage("json");
                query.setText(shape.jsonQuery());
                design.setQuery(query);
            }
            JasperReport report = JasperCompileManager.compileReport(design);
            log.debug("Compiled Jasper template {}{}{}", location,
                    shape.columns() == null ? "" : " with " + shape.columns().size() + " generated columns",
                    shape.chrome() ? "" : " (table only)");
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
