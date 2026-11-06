package com.creed.resource.veracode.service;

import com.creed.resource.veracode.domain.ReportRow;
import com.creed.resource.veracode.service.VeracodeXmlParser.ParsedReport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VeracodeXmlParserTest {

    private final VeracodeXmlParser parser = new VeracodeXmlParser();

    private ParsedReport parse(String xml) {
        return parser.parse("r.xml", xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsOnlyTheScaSectionWithAnyNamespace() {
        ParsedReport report = parse("""
                <detailedreport xmlns="https://www.veracode.com/schema/reports/export/9.9" app_name="shop" version="1.0"
                                policy_name="P" policy_compliance_status="Pass">
                  <component library="not-sca" version="1"/>
                  <software_composition_analysis><vulnerable_components>
                    <component library="lib" version="1.2" file_name="lib-1.2.jar">
                      <file_paths><file_path value="a.war#lib-1.2.jar"/><file_path value="b.war#lib-1.2.jar"/></file_paths>
                      <vulnerabilities>
                        <vulnerability cve_id="cve-2024-1234" cwe_id="CWE-79" cvss_score="7.1" severity="4"
                                       cve_summary="First sentence here. Second one."/>
                        <vulnerability cve_id="SRCCLR-SID-1" severity="2" cvss_score="n/a" cve_summary="No CVE."/>
                        <vulnerability cwe_id="cwe-400" severity="3" cve_summary="Neither id."/>
                      </vulnerabilities>
                    </component>
                  </vulnerable_components></software_composition_analysis>
                </detailedreport>
                """);

        assertThat(report.info().appName()).isEqualTo("shop");
        assertThat(report.info().version()).isEqualTo("1.0");
        assertThat(report.info().policyCompliance()).isEqualTo("Pass");
        assertThat(report.rows()).hasSize(3);
        ReportRow cve = report.rows().getFirst();
        assertThat(cve.application()).isEqualTo("shop");
        assertThat(cve.cveId()).isEqualTo("CVE-2024-1234");
        assertThat(cve.cwe()).isEqualTo("CWE-79");
        assertThat(cve.fileName()).isEqualTo("lib-1.2.jar");
        // the whole cve_summary, not its first sentence
        assertThat(cve.summary()).isEqualTo("First sentence here. Second one.");
        assertThat(cve.component()).isEqualTo("lib 1.2");
        assertThat(cve.severity()).isEqualTo("High");
        assertThat(cve.cvss()).isEqualTo(7.1);
        assertThat(cve.location()).isEqualTo("lib 1.2 (a.war#lib-1.2.jar)");
        assertThat(cve.affectedVersion()).isNull();
        ReportRow advisory = report.rows().get(1);
        // an advisory id is the cve_id as given
        assertThat(advisory.cveId()).isEqualTo("SRCCLR-SID-1");
        assertThat(advisory.cwe()).isNull();
        assertThat(advisory.severity()).isEqualTo("Low");
        ReportRow unknown = report.rows().get(2);
        assertThat(unknown.cveId()).isNull();
        assertThat(unknown.cwe()).isEqualTo("CWE-400");
        assertThat(advisory.cvss()).isNull();
        assertThat(report.warnings()).containsExactly("lib 1.2 (a.war#lib-1.2.jar): CVSS 'n/a' is not a number, left empty");
        assertThat(report.withVersion("1.0").rows()).allMatch(r -> "1.0".equals(r.affectedVersion()));
    }

    @Test
    void aReportWithoutScaFindingsSaysSo() {
        assertThat(parse("<detailedreport version=\"1\"/>").warnings()).singleElement().asString()
                .startsWith("the report has no vulnerable components");
    }

    @Test
    void rejectsWhatIsNotADetailedReport() {
        assertThatThrownBy(() -> parse("<project/>")).isInstanceOf(VeracodeXmlParser.ReportFormatException.class)
                .hasMessageContaining("<project>");
        assertThatThrownBy(() -> parse("CVE,Title\n")).isInstanceOf(VeracodeXmlParser.UnsupportedFileException.class);
    }

    @Test
    void refusesADoctype() {
        // XXE: the file comes from a user
        assertThatThrownBy(() -> parse("""
                <?xml version="1.0"?>
                <!DOCTYPE detailedreport [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <detailedreport version="1">&x;</detailedreport>
                """)).isInstanceOf(VeracodeXmlParser.UnsupportedFileException.class);
    }
}
