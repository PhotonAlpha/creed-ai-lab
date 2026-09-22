package com.creed.jasper.service;

import com.creed.jasper.domain.ApprovalStatusReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The hard-coded approval-status payload, transcribed from {@code creed-report/docs/template.jpg}.
 *
 * <p><b>Byte-for-byte the JSON in creed-report's {@code ApprovalStatusReportController}</b>, and
 * that is the point: this module exists so the same document can be laid out by a second engine,
 * and a sample that drifted would make every comparison between the two PDFs meaningless. If one
 * side's payload changes, so must the other's.
 *
 * <p><b>Input-less by design</b>, for the same reason it is there: the endpoint pins a <i>layout</i>
 * down, so two calls a week apart differ only in the export timestamp — which is what makes it
 * usable as a reference render and as a regression test.
 *
 * <p>Thirteen rows because the sample says "13 Record(s)" and prints "1 of 2": the document has to
 * be two pages for the repeated page header, the footer and the page counter to be observable at
 * all. A three-row sample would prove nothing.
 */
public final class ApprovalStatusSamples {

    private ApprovalStatusSamples() {
    }

    private static final String SAMPLE_JSON = """
            {
              "title": "Approval Status All List",
              "criteria": [
                { "label": "Transaction / Deposit Type", "value": "--" },
                { "label": "Bank Reference",             "value": "--" },
                { "label": "Customer Reference",         "value": "--" },
                { "label": "Account",                    "value": "--" },
                { "label": "Currency",                   "value": "--" },
                { "label": "Amount",                     "value": "--" },
                { "label": "Value / Placement Date",     "value": "--" },
                { "label": "Application Date",           "value": "05/07/2026 - 02/09/2026" },
                { "label": "Payer / Payee",              "value": "--" },
                { "label": "Status",                     "value": "--" }
              ],
              "note": "13 Record(s) (Note: This is a filtered table.)",
              "rows": [
                { "type": "Bulk MEPS", "bankReference": "BK2600000001",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Processing" },
                { "type": "Bulk MEPS", "bankReference": "BK2600000002",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Processing" },
                { "type": "Bulk MEPS", "bankReference": "BK2600000003",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Processing" },
                { "type": "Bulk MEPS", "bankReference": "BK2600000004",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Processing" },
                { "type": "Bulk MEPS", "bankReference": "BK2600000005",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Processing" },
                { "type": "Bulk MEPS", "bankReference": "BK2600000006",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Processing" },
                { "type": "Bulk MEPS", "bankReference": "BK2600000007",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Processing" },
                { "type": "Bulk MEPS", "bankReference": "BK2600000008",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Processing" },
                { "type": "Bulk GIRO", "bankReference": "BK2600000009",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Processing" },
                { "type": "Bulk GIRO", "bankReference": "BK2600000010",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Processing" },
                { "type": "Bulk GIRO", "bankReference": "BK2600000011",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Approved" },
                { "type": "Bulk GIRO", "bankReference": "BK2600000012",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Approved" },
                { "type": "Telegraphic Transfer", "bankReference": "BK2600000013",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "status": "Rejected" }
              ]
            }
            """;

    /** The sample, parsed. */
    public static ApprovalStatusReport report(ObjectMapper objectMapper) {
        try {
            return objectMapper.readValue(SAMPLE_JSON, ApprovalStatusReport.class);
        }
        catch (JsonProcessingException ex) {
            // The JSON is a constant in this file: if it does not parse, the build is broken, not
            // the request. Fail loudly rather than serving an empty document.
            throw new IllegalStateException("The hard-coded approval-status sample is not valid JSON", ex);
        }
    }
}
