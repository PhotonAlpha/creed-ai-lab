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
    void summarisesTheScaSection() {
        ParsedReport report = parse("""
                <detailedreport app_name="shop" version="1.0">
                  <software_composition_analysis third_party_components="40" violate_policy="true"
                                                 components_violated_policy="2" blacklisted_components="x">
                    <vulnerable_components>
                      <component library="a" file_name="a.jar"><vulnerabilities>
                        <vulnerability cve_id="CVE-1" severity="5" cvss_score="9.8"/>
                        <vulnerability cve_id="CVE-2" severity="4" cvss_score="7.5"/>
                        <vulnerability cve_id="CVE-3" severity="4" mitigation="true" cvss_score="10.0"/>
                      </vulnerabilities></component>
                      <component library="b" file_name="b.jar"><vulnerabilities>
                        <vulnerability cve_id="CVE-4" severity_desc="Whatever"/>
                        <vulnerability cve_id="CVE-5" severity="0"/>
                      </vulnerabilities></component>
                    </vulnerable_components>
                  </software_composition_analysis>
                </detailedreport>
                """);

        ScaSummary sca = report.sca();
        assertThat(sca.thirdPartyComponents()).isEqualTo(40);
        assertThat(sca.violatesPolicy()).isTrue();
        assertThat(sca.componentsViolatedPolicy()).isEqualTo(2);
        // not a number: empty and a warning, never a failed upload
        assertThat(sca.blacklistedComponents()).isNull();
        assertThat(report.warnings()).anyMatch(w -> w.contains("blacklisted_components 'x'"));
        assertThat(sca.vulnerableComponents()).isEqualTo(2);
        // the mitigated one is neither counted nor the highest CVSS
        assertThat(sca.vulnerabilities()).isEqualTo(4);
        assertThat(sca.mitigated()).isEqualTo(1);
        assertThat(sca.maxCvss()).isEqualTo(9.8);
        assertThat(sca.bySeverity()).extracting(ScaSummary.SeverityCount::severity)
                .containsExactlyElementsOf(ScaSummary.LEVELS);
        assertThat(sca.bySeverity()).extracting(ScaSummary.SeverityCount::count).containsExactly(1, 1, 0, 0, 0, 1);
        assertThat(sca.unknownSeverity()).isEqualTo(1);
    }

    @Test
    void aReportWithoutAnScaSectionHasNoStatedCounts() {
        ScaSummary sca = parse("<detailedreport app_name=\"shop\"/>").sca();
        assertThat(sca.thirdPartyComponents()).isNull();
        assertThat(sca.violatesPolicy()).isNull();
        assertThat(sca.vulnerabilities()).isZero();
        assertThat(sca.maxCvss()).isNull();
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
