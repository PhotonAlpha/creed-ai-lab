# TODO

## creed-report — 导出功能（已完成）

- [x] 导出 PDF —— `PdfExportService`，openpdf-html + 专用 `*-pdf.html` 模板（commit `c26005a`、`290092a`）
- [x] 导出 Excel —— POI，`com.creed.report.export`（commit `6ab6df9`）
- [x] 根据策略，导出相应格式的报表 —— `ReportType` 枚举为策略键、`ExcelReportExporter` 为策略、
      `ExcelExportService` 为上下文；**新增一种报表 = 一个枚举常量 + 一个 `@Component` 导出器**，
      `/report` 页面的下拉框由 `supportedTypes()` 驱动，新类型自动出现
- [x] 导出文案国际化（en / zh_CN / zh_TW）（commit `7b2ca7a`）

> 原文件把「导出Excel」重复列了 5 次且全部未勾选，与实际状态不符 —— 上述功能均已合入。
> 详见 `creed-report/HANDOFF.md` 与 `creed-report/README.md`。

## 待办

_暂无。新增待办请按模块分组；完成时同步对应的 `<module>/HANDOFF.md`。_
