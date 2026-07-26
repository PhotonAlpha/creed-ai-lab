package com.creed.report.export;

import com.creed.report.model.EnvironmentSnapshot;
import com.creed.report.model.PropertyEntry;
import com.creed.report.model.PropertySourceView;
import com.creed.report.service.EnvironmentInspectionService;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link ReportType#ENVIRONMENT} strategy: the Environment Inspector result as a four-sheet
 * workbook — Summary, the effective (precedence-resolved) properties, the property sources, and
 * every property of every source with a flag marking which occurrence actually won.
 *
 * <p>The inspection parameters are read off the request with the same names the {@code /environment}
 * page uses ({@code spring.profiles.active}, {@code spring.config.location},
 * {@code spring.config.additional-location}), so a link can carry the current view's parameters
 * straight into the export; missing ones fall back to
 * {@link EnvironmentInspectionService}'s defaults.
 */
@Component
public class EnvironmentExcelExporter implements ExcelReportExporter {

    private final EnvironmentInspectionService inspectionService;
    private final MessageSource messageSource;

    public EnvironmentExcelExporter(EnvironmentInspectionService inspectionService, MessageSource messageSource) {
        this.inspectionService = inspectionService;
        this.messageSource = messageSource;
    }

    @Override
    public ReportType reportType() {
        return ReportType.ENVIRONMENT;
    }

    @Override
    public void write(Workbook workbook, ExcelExportRequest request) {
        Locale locale = request.locale();
        String profilesActive = request.parameter("spring.profiles.active",
                EnvironmentInspectionService.DEFAULT_PROFILES_ACTIVE);
        String configLocation = request.parameter("spring.config.location",
                EnvironmentInspectionService.DEFAULT_CONFIG_LOCATION);
        String additionalLocation = request.parameter("spring.config.additional-location",
                EnvironmentInspectionService.DEFAULT_ADDITIONAL_LOCATION);

        EnvironmentSnapshot snapshot =
                inspectionService.inspect(profilesActive, configLocation, additionalLocation);
        ExcelStyles styles = new ExcelStyles(workbook);

        writeSummary(workbook, styles, request, snapshot, profilesActive, configLocation, additionalLocation);
        writeEffective(workbook, styles, locale, snapshot);
        writeSources(workbook, styles, locale, snapshot);
        writeAllProperties(workbook, styles, locale, snapshot);
    }

    private void writeSummary(Workbook workbook, ExcelStyles styles, ExcelExportRequest request,
                              EnvironmentSnapshot snapshot, String profilesActive,
                              String configLocation, String additionalLocation) {
        Locale locale = request.locale();
        ExcelSheetBuilder.create(workbook, styles, msg("excel.sheet.summary", locale))
                .title(msg("excel.env.title", locale))
                .caption(msg("report.generatedAt", locale) + ": " + request.generatedAtText())
                .blank()
                .labelled(msg("excel.env.activeProfiles", locale), join(snapshot.activeProfiles()))
                .labelled(msg("excel.env.defaultProfiles", locale), join(snapshot.defaultProfiles()))
                .labelled(msg("excel.env.sourceCount", locale), snapshot.propertySources().size())
                .labelled(msg("excel.env.effectiveCount", locale), snapshot.effective().size())
                .blank()
                .labelled(msg("excel.env.param.profiles", locale), profilesActive)
                .labelled(msg("excel.env.param.location", locale), configLocation)
                .labelled(msg("excel.env.param.additionalLocation", locale), additionalLocation)
                .finish();
    }

    /** Effective view — one row per key, sorted, as the rendered environment page presents it. */
    private void writeEffective(Workbook workbook, ExcelStyles styles, Locale locale,
                                EnvironmentSnapshot snapshot) {
        ExcelSheetBuilder sheet = ExcelSheetBuilder.create(workbook, styles, msg("excel.sheet.effective", locale))
                .header(msg("excel.col.key", locale),
                        msg("excel.col.resolved", locale),
                        msg("excel.col.raw", locale),
                        msg("excel.col.source", locale),
                        msg("excel.col.unresolved", locale));

        snapshot.effective().stream()
                .sorted(Comparator.comparing(PropertyEntry::name))
                .forEach(entry -> sheet.row(entry.name(), entry.resolved(), entry.raw(), entry.source(),
                        bool(entry.unresolved(), locale)));
        sheet.finish();
    }

    /** Property sources in precedence order, highest first. */
    private void writeSources(Workbook workbook, ExcelStyles styles, Locale locale,
                              EnvironmentSnapshot snapshot) {
        ExcelSheetBuilder sheet = ExcelSheetBuilder.create(workbook, styles, msg("excel.sheet.sources", locale))
                .header(msg("excel.col.precedence", locale),
                        msg("excel.col.sourceName", locale),
                        msg("excel.col.sourceType", locale),
                        msg("excel.col.propertyCount", locale));

        int precedence = 1;
        for (PropertySourceView source : snapshot.propertySources()) {
            sheet.row(precedence++, source.name(), source.type(), source.properties().size());
        }
        sheet.finish();
    }

    /**
     * Every property of every source, including the ones a higher-precedence source shadows —
     * the flag column is what the flat effective view cannot show, and it is the usual reason to
     * open this report in Excel and filter.
     */
    private void writeAllProperties(Workbook workbook, ExcelStyles styles, Locale locale,
                                    EnvironmentSnapshot snapshot) {
        // The winning source per key; an occurrence is effective when it comes from that source.
        Map<String, String> winner = new HashMap<>();
        for (PropertyEntry entry : snapshot.effective()) {
            winner.put(entry.name(), entry.source());
        }

        ExcelSheetBuilder sheet = ExcelSheetBuilder.create(workbook, styles, msg("excel.sheet.allProperties", locale))
                .header(msg("excel.col.source", locale),
                        msg("excel.col.key", locale),
                        msg("excel.col.resolved", locale),
                        msg("excel.col.raw", locale),
                        msg("excel.col.effective", locale));

        for (PropertySourceView source : snapshot.propertySources()) {
            for (PropertyEntry entry : source.properties()) {
                boolean effective = source.name().equals(winner.get(entry.name()));
                sheet.row(source.name(), entry.name(), entry.resolved(), entry.raw(), bool(effective, locale));
            }
        }
        sheet.finish();
    }

    private String join(List<String> values) {
        return (values == null || values.isEmpty()) ? "" : String.join(", ", values);
    }

    private String bool(boolean value, Locale locale) {
        return msg(value ? "excel.value.yes" : "excel.value.no", locale);
    }

    private String msg(String key, Locale locale) {
        return messageSource.getMessage(key, null, locale);
    }
}
