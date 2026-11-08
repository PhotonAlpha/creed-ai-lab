package com.creed.resource.veracode.service;

import com.creed.resource.veracode.domain.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JiraDescriptionTest {

    private static final ReportView REPORT = new ReportView("r.xml", "app", null, "2.3.0", "2.3.0", false, null, null, null, null, null);

    @Test
    void noPipeInACellSplitsTheRow() {
        Finding f = new Finding("app|Unknown|CWE-79@a.jar", "app", null, null, "CWE-79", "a.jar", List.of("a 1"), "High",
                7.5, "2.3.0", "Input | output [mixed]\nsecond line", List.of("a 1 (x.war)"));

        String d = JiraDescription.of(f, REPORT);

        // Jira Cloud shows "\|" as a backslash and a new cell: CVE and CWE are two rows, a pipe in text is ¦
        assertThat(d).doesNotContain("\\|")
                .contains("|CVE|Unknown|\n", "|CWE|[CWE-79|https://cwe.mitre.org/data/definitions/79.html]|\n",
                        "|Description|Input ¦ output \\[mixed\\] second line|\n");
        // every row has exactly its two cells
        d.lines().filter(l -> l.startsWith("|") && !l.startsWith("||")).forEach(l ->
                assertThat(l.replaceAll("\\[[^\\]]*\\|[^\\]]*\\]", "LINK").chars().filter(c -> c == '|').count())
                        .as(l).isEqualTo(3));
    }
}
