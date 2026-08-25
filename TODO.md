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


## 备注

```text
git@github.com:PhotonAlpha/creed-ai-lab.git

LS0tLS1CRUdJTiBPUEVOU1NIIFBSSVZBVEUgS0VZLS0tLS0KYjNCbGJuTnphQzFyWlhrdGRqRUFBQUFBQ21GbGN6STFOaTFqZEhJQUFBQUdZbU55ZVhCMEFBQUFHQUFBQUJBaTUyb3JucApQM3RiR0NFaU5wY0E0NkFBQUFHQUFBQUFFQUFBQXpBQUFBQzNOemFDMWxaREkxTlRFNUFBQUFJT0d4blRoamJibjJvcUg1CjJEaktPbGxLeHRsS2tNSVVYOUxrQVM3Y0ZXYkJBQUFBb0RlT2J2Yk9JQythc1VCamdxYTdjSmVtd0U2SjB0RUVlTlM2eEoKdXFyNGFFZ0xVU1RWSkNoTmlGWDZtTXRVWjBYMmdtai9JVEFMUnlPKzIxbTVTMGN4TnFENm1lNERuUGdaOUhkOUltY3JDYQprUDNvQkxEdW1UME9qQVVIWUI3VFhyZk9LejhOTzVyVHdHcGVRRTU5K0oyMDRFd1FBN0FoNThhMmFVanQzU1A2dHJ0R1FHCjhLeFlPUnc3TTU1UEo2WWFGdHJ1NUR0OERqcG1NL3B4ME1tT1U9Ci0tLS0tRU5EIE9QRU5TU0ggUFJJVkFURSBLRVktLS0tLQo=
```
