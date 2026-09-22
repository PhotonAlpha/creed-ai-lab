package com.creed.jasper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * <b>creed-jasper-report</b> — the approval-status listing of {@code creed-report/docs/template.jpg},
 * laid out by <b>JasperReports</b> from a {@code .jrxml} instead of by Flying Saucer from Thymeleaf
 * + CSS.
 *
 * <p>It is deliberately the <i>same document</i> as
 * {@code creed-report}'s {@code approval-status-export-pdf.html}, down to the sample payload: the
 * point of the module is that the two can be put side by side, so everything they can share —
 * the JSON, the Noto faces, the logo and the seal — <b>is</b> shared, and a difference between the
 * two PDFs is the layout engine rather than the inputs.
 *
 * <p>Standalone like creed-report: plain HTTP {@code 9110}, context path {@code /jasper-report}, no
 * OAuth2, no mTLS, no config server.
 */
@SpringBootApplication
public class CreedJasperReportApplication {

    public static void main(String[] args) {
        SpringApplication.run(CreedJasperReportApplication.class, args);
    }
}
