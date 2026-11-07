package com.creed.parent.controller.vo;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2026-06-18T16
 */
public record ReportPropertiesOverviewVo(PropertiesOverviewVo nonMatchingPropertiesOverview,
                                         PropertiesOverviewVo matchingPropertiesOverview,
                                         PropertiesOverviewVo overallPropertiesOverview,
                                         OverallSummaryVo overallSummary) {

    /**
     * Without the overall comparison — what {@code PdfController} still builds. The template only
     * renders the "Overall Comparison" tab when {@code overallPropertiesOverview} is present.
     */
    public ReportPropertiesOverviewVo(PropertiesOverviewVo nonMatchingPropertiesOverview, PropertiesOverviewVo matchingPropertiesOverview) {
        this(nonMatchingPropertiesOverview, matchingPropertiesOverview, null, null);
    }
}
