package com.creed.jasper.service;

import com.creed.jasper.domain.ApprovalStatusReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The hard-coded approval-status payload, transcribed from {@code creed-report/docs/template.jpg}.
 *
 * <p><b>This payload has DIVERGED from creed-report's</b>, and knowingly. It was byte-for-byte the
 * JSON in that module's {@code ApprovalStatusReportController} — the point being that the same
 * document laid out by two engines makes every difference between the two PDFs a difference of
 * layout engine and nothing else. The four columns added here (customer reference, currency,
 * amount, value date) exist on this side only, so until creed-report's sample grows the same four
 * fields the two PDFs are no longer comparable row for row. The chrome still is: title band,
 * criteria block, footnote and page counter are unchanged on both sides.
 *
 * <p><b>Input-less by design</b>, for the same reason it is there: the endpoint pins a <i>layout</i>
 * down, so two calls a week apart differ only in the export timestamp — which is what makes it
 * usable as a reference render and as a regression test.
 *
 * <p>Thirteen rows because the sample says "13 Record(s)": the document has to run to more than one
 * page for the repeated page header, the footer and the page counter to be observable at all. A
 * three-row sample would prove nothing.
 *
 * <p><b>Eight columns, and four of them carry the variation.</b> The currency is not always the
 * account's (rows 3, 6 and 12 are FX), the amounts run from three figures to six so the
 * right-aligned column has something to align, and the value dates spread across the criteria
 * block's own 05/07/2026 - 02/09/2026 range. A sample whose every cell is the same string cannot
 * show a column that is too narrow, which is exactly what eight columns on A4 makes possible.
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
                { "type": "Bulk MEPS", "bankReference": "BK2600000001", "customerReference": "CUST26000001",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "currency": "SGD", "amount": "12,345.67", "valueDate": "05/07/2026",
                  "status": "Processing" },
                { "type": "Bulk MEPS", "bankReference": "BK2600000002", "customerReference": "CUST26000002",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "currency": "SGD", "amount": "8,900.00", "valueDate": "06/07/2026",
                  "status": "Processing" },
                { "type": "Bulk MEPS", "bankReference": "BK2600000003", "customerReference": "CUST26000003",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "currency": "USD", "amount": "145,000.00", "valueDate": "09/07/2026",
                  "status": "Processing" },
                { "type": "Bulk MEPS", "bankReference": "BK2600000004", "customerReference": "CUST26000004",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "currency": "SGD", "amount": "2,310.45", "valueDate": "13/07/2026",
                  "status": "Processing" },
                { "type": "Bulk MEPS", "bankReference": "BK2600000005", "customerReference": "CUST26000005",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "currency": "SGD", "amount": "76,540.00", "valueDate": "20/07/2026",
                  "status": "Processing" },
                { "type": "Bulk MEPS", "bankReference": "BK2600000006", "customerReference": "CUST26000006",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "currency": "EUR", "amount": "19,875.30", "valueDate": "24/07/2026",
                  "status": "Processing" },
                { "type": "Bulk MEPS", "bankReference": "BK2600000007", "customerReference": "CUST26000007",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "currency": "SGD", "amount": "505.00", "valueDate": "31/07/2026",
                  "status": "Processing" },
                { "type": "Bulk MEPS", "bankReference": "BK2600000008", "customerReference": "CUST26000008",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "currency": "SGD", "amount": "63,120.80", "valueDate": "05/08/2026",
                  "status": "Processing" },
                { "type": "Bulk GIRO", "bankReference": "BK2600000009", "customerReference": "CUST26000009",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "currency": "SGD", "amount": "4,499.99", "valueDate": "07/08/2026",
                  "status": "Processing" },
                { "type": "Bulk GIRO", "bankReference": "BK2600000010", "customerReference": "CUST26000010",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "currency": "SGD", "amount": "28,000.00", "valueDate": "12/08/2026",
                  "status": "Processing" },
                { "type": "Bulk GIRO", "bankReference": "BK2600000011", "customerReference": "CUST26000011",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "currency": "SGD", "amount": "950.00", "valueDate": "18/08/2026",
                  "status": "Approved" },
                { "type": "Bulk GIRO", "bankReference": "BK2600000012", "customerReference": "CUST26000012",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "currency": "USD", "amount": "310,750.25", "valueDate": "25/08/2026",
                  "status": "Approved" },
                { "type": "Telegraphic Transfer", "bankReference": "BK2600000013", "customerReference": "CUST26000013",
                  "account": ["VASA COMPANY 3 WITH ACCOUNT", "NAME MO", "1013672712", "SGD"],
                  "currency": "SGD", "amount": "15,000.00", "valueDate": "02/09/2026",
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
