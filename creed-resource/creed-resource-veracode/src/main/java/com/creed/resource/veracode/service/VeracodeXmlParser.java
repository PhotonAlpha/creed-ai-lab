package com.creed.resource.veracode.service;

import com.creed.resource.veracode.domain.ReportRow;
import com.creed.resource.veracode.domain.Severity;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * A Veracode <em>Detailed Report</em> XML ({@code <detailedreport>}, schema
 * {@code https://www.veracode.com/schema/reports/export/1.0}) reduced to its SCA findings: one
 * {@link ReportRow} per {@code software_composition_analysis/vulnerable_components/component/vulnerabilities/vulnerability}.
 *
 * <p>The application's version - Jira's "Affects Version/s" - is the root's {@code version} (the scan / build
 * name), not the component's: {@code component/@version} is the <em>library's</em> version and goes into
 * the component label ({@code log4j-core 2.14.1}). The page can override it when the scan name is not a
 * release ({@code "16 Oct 2026 Static"}).
 *
 * <p>Streamed with StAX, not a DOM: a detailed report also carries every static flaw and can run to tens of
 * megabytes, of which only the SCA section is read. Elements are matched by local name, so the namespace
 * (and its version) does not matter. DTDs and external entities are off - the file comes from a user.
 *
 * <p>Mitigated vulnerabilities ({@code mitigation="true"}: an approved mitigation in Veracode) are skipped
 * with a warning - filing them would reopen an accepted risk.
 */
@Component
public class VeracodeXmlParser {

    private static final String[] SEVERITY = {"Informational", "Very Low", "Low", "Medium", "High", "Very High"};

    /**
     * The report's own description of itself, from {@code <detailedreport>}'s attributes.
     *
     * @param version the scan / build name ({@code version}); {@code null} when the report has none
     */
    public record ReportInfo(String appName, String appId, String version, String buildId, String policyName,
                             String policyCompliance, String generationDate) {
    }

    /** {@code rows} carry no affected version yet: {@link #withVersion} sets it once the effective one is known. */
    public record ParsedReport(ReportInfo info, List<ReportRow> rows, List<String> warnings, ScaSummary sca) {

        public ParsedReport withVersion(String affectedVersion) {
            return new ParsedReport(info, rows.stream().map(r -> r.withVersion(affectedVersion)).toList(), warnings,
                    sca);
        }
    }

    public ParsedReport parse(String filename, byte[] content) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        try {
            XMLStreamReader xml = factory.createXMLStreamReader(new ByteArrayInputStream(content));
            try {
                return read(xml);
            } finally {
                xml.close();
            }
        } catch (XMLStreamException e) {
            throw new UnsupportedFileException(filename + " is not well-formed XML: " + e.getMessage());
        }
    }

    private ParsedReport read(XMLStreamReader xml) throws XMLStreamException {
        ReportInfo info = null;
        List<ReportRow> rows = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Deque<String> path = new ArrayDeque<>();
        String application = null;
        String component = null;
        String componentFile = null;
        String filePath = null;
        int components = 0;
        int mitigated = 0;
        int vulnerabilities = 0;
        // <software_composition_analysis>'s own statistics, and the per-level counts (Very High first)
        Integer thirdParty = null;
        Boolean violatesPolicy = null;
        Integer violatedPolicy = null;
        Integer blacklisted = null;
        int[] bySeverity = new int[ScaSummary.LEVELS.size()];
        int unknownSeverity = 0;
        Double maxCvss = null;
        while (xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.END_ELEMENT) {
                if ("component".equals(path.pop())) {
                    component = null;
                    filePath = null;
                }
                continue;
            }
            if (event != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            String name = xml.getLocalName();
            String parent = path.peek();
            path.push(name);
            if (info == null) {
                if (!"detailedreport".equals(name)) {
                    throw new ReportFormatException("not a Veracode detailed report: the root element is <" + name
                            + ">, expected <detailedreport>");
                }
                application = attr(xml, "app_name");
                info = new ReportInfo(application, attr(xml, "app_id"), attr(xml, "version"),
                        attr(xml, "build_id"), attr(xml, "policy_name"), attr(xml, "policy_compliance_status"),
                        attr(xml, "generation_date"));
                continue;
            }
            if ("software_composition_analysis".equals(name)) {
                thirdParty = count(xml, "third_party_components", warnings);
                String violate = attr(xml, "violate_policy");
                violatesPolicy = violate == null ? null : Boolean.valueOf(violate);
                violatedPolicy = count(xml, "components_violated_policy", warnings);
                blacklisted = count(xml, "blacklisted_components", warnings);
            } else if ("component".equals(name) && "vulnerable_components".equals(parent)) {
                components++;
                String library = attr(xml, "library");
                String version = attr(xml, "version");
                componentFile = attr(xml, "file_name");
                component = library != null ? (version != null ? library + " " + version : library)
                        : componentFile != null ? componentFile : "component " + components;
            } else if ("file_path".equals(name) && component != null && filePath == null) {
                filePath = attr(xml, "value");
            } else if ("vulnerability".equals(name) && component != null && "vulnerabilities".equals(parent)) {
                if ("true".equalsIgnoreCase(attr(xml, "mitigation"))) {
                    mitigated++;
                    continue;
                }
                vulnerabilities++;
                ReportRow row = row(xml, application, component, componentFile, filePath, vulnerabilities, warnings);
                if (row != null) {
                    rows.add(row);
                    int rank = Severity.rank(row.severity());
                    if (rank >= 0) {
                        bySeverity[ScaSummary.LEVELS.size() - 1 - rank]++;
                    } else {
                        unknownSeverity++;
                    }
                    if (row.cvss() != null && (maxCvss == null || row.cvss() > maxCvss)) {
                        maxCvss = row.cvss();
                    }
                }
            }
        }
        if (info == null) {
            throw new ReportFormatException("the file is empty");
        }
        if (components == 0) {
            warnings.add("the report has no vulnerable components (no software_composition_analysis section, or SCA"
                    + " found nothing)");
        }
        if (mitigated > 0) {
            warnings.add(mitigated + " mitigated vulnerabilit" + (mitigated == 1 ? "y" : "ies") + " skipped");
        }
        List<ScaSummary.SeverityCount> levels = new ArrayList<>();
        for (int i = 0; i < bySeverity.length; i++) {
            levels.add(new ScaSummary.SeverityCount(ScaSummary.LEVELS.get(i), bySeverity[i]));
        }
        ScaSummary sca = new ScaSummary(thirdParty, violatesPolicy, violatedPolicy, blacklisted, components,
                rows.size(), mitigated, List.copyOf(levels), unknownSeverity, maxCvss);
        return new ParsedReport(info, List.copyOf(rows), List.copyOf(warnings), sca);
    }

    private static ReportRow row(XMLStreamReader xml, String application, String component, String componentFile,
                                 String filePath, int n, List<String> warnings) {
        String id = attr(xml, "cve_id");
        String cwe = attr(xml, "cwe_id");
        String summary = attr(xml, "cve_summary");
        String path = filePath != null ? filePath : componentFile;
        String location = component + (path == null ? "" : " (" + path + ")");
        // the jar is the second half of the issue's identity; a component without file_name falls back to its label
        String fileName = componentFile != null ? componentFile : component;
        if (id == null && cwe == null) {
            warnings.add(location + ": vulnerability " + n + " has neither a cve_id nor a cwe_id - kept as Unknown,"
                    + " but it cannot be looked up in Jira");
        }
        return new ReportRow(location, application, id == null ? null : id.toUpperCase(Locale.ROOT),
                cwe == null ? null : cwe.toUpperCase(Locale.ROOT), fileName, component, severity(xml),
                cvss(xml, location, warnings), null, summary);
    }

    private static String severity(XMLStreamReader xml) {
        String number = attr(xml, "severity");
        if (number != null && number.matches("[0-5]")) {
            return SEVERITY[Integer.parseInt(number)];
        }
        return attr(xml, "severity_desc");
    }

    private static Double cvss(XMLStreamReader xml, String location, List<String> warnings) {
        String raw = attr(xml, "cvss_score");
        if (raw == null) {
            return null;
        }
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException e) {
            warnings.add(location + ": CVSS '" + raw + "' is not a number, left empty");
            return null;
        }
    }

    /** A count attribute; one that is not a number is a warning and {@code null}, never a failed upload. */
    private static Integer count(XMLStreamReader xml, String name, List<String> warnings) {
        String raw = attr(xml, name);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            warnings.add("software_composition_analysis: " + name + " '" + raw + "' is not a number, left empty");
            return null;
        }
    }

    private static String attr(XMLStreamReader xml, String name) {
        String value = xml.getAttributeValue(null, name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** XML, but not a report this service can read. */
    public static class ReportFormatException extends RuntimeException {
        public ReportFormatException(String message) {
            super(message);
        }
    }

    /** Not XML at all. */
    public static class UnsupportedFileException extends RuntimeException {
        public UnsupportedFileException(String message) {
            super(message);
        }
    }
}
