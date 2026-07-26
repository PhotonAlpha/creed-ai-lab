package com.creed.report.export;

import com.creed.report.model.ServerInfo;
import com.creed.report.service.ServerInfoService;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * {@link ReportType#SERVER_INVENTORY} strategy: the server table of {@code /report}, as a single
 * sheet with the same columns and the same localized headers as the page and the PDF.
 */
@Component
public class ServerInventoryExcelExporter implements ExcelReportExporter {

    private final ServerInfoService serverInfoService;
    private final MessageSource messageSource;

    public ServerInventoryExcelExporter(ServerInfoService serverInfoService, MessageSource messageSource) {
        this.serverInfoService = serverInfoService;
        this.messageSource = messageSource;
    }

    @Override
    public ReportType reportType() {
        return ReportType.SERVER_INVENTORY;
    }

    @Override
    public void write(Workbook workbook, ExcelExportRequest request) {
        Locale locale = request.locale();
        List<ServerInfo> servers = serverInfoService.listServers();
        ExcelStyles styles = new ExcelStyles(workbook);

        ExcelSheetBuilder sheet = ExcelSheetBuilder.create(workbook, styles, msg("excel.sheet.servers", locale))
                .title(msg("report.brand", locale))
                .caption(msg("report.generatedAt", locale) + ": " + request.generatedAtText()
                         + "  |  " + msg("report.total", locale) + ": " + servers.size())
                .blank()
                .header(msg("excel.col.index", locale),
                        msg("report.col.host", locale),
                        msg("report.col.ip", locale),
                        msg("report.col.app", locale),
                        msg("report.col.country", locale),
                        msg("report.col.service", locale),
                        msg("report.col.env", locale),
                        msg("report.col.zone", locale),
                        msg("report.col.slot", locale));

        int index = 1;
        for (ServerInfo server : servers) {
            sheet.row(index++, server.host(), server.ip(), server.app(), server.country(),
                    server.service(), server.env(), server.zone(), server.slot());
        }
        if (servers.isEmpty()) {
            sheet.row(msg("report.empty", locale));
        }
        sheet.finish();
    }

    private String msg(String key, Locale locale) {
        return messageSource.getMessage(key, null, locale);
    }
}
