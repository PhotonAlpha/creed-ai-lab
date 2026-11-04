package com.creed.resource.veracode.service;

import com.creed.resource.veracode.domain.ReportRow;
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
import java.util.regex.Pattern;

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

    private static final Pattern CVE = Pattern.compile("CVE-\\d{4}-\\d{4,}", Pattern.CASE_INSENSITIVE);
    /** First sentence: up to the first ". " / ".\n", or the whole text. */
    private static final Pattern SENTENCE_END = Pattern.compile("\\.(\\s|$)");
    private static final int TITLE_MAX = 160;
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
    public record ParsedReport(ReportInfo info, List<ReportRow> rows, List<String> warnings) {

        public ParsedReport withVersion(String affectedVersion) {
            return new ParsedReport(info, rows.stream().map(r -> new ReportRow(r.location(), r.cve(), r.title(),
                    r.component(), r.severity(), r.cvss(), affectedVersion, r.fixedVersion(), r.description())).toList(),
                    warnings);
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
        String component = null;
        String componentFile = null;
        String filePath = null;
        int components = 0;
        int mitigated = 0;
        int vulnerabilities = 0;
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
                info = new ReportInfo(attr(xml, "app_name"), attr(xml, "app_id"), attr(xml, "version"),
                        attr(xml, "build_id"), attr(xml, "policy_name"), attr(xml, "policy_compliance_status"),
                        attr(xml, "generation_date"));
                continue;
            }
            if ("component".equals(name) && "vulnerable_components".equals(parent)) {
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
                ReportRow row = row(xml, component, filePath != null ? filePath : componentFile, vulnerabilities, warnings);
                if (row != null) {
                    rows.add(row);
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
        return new ParsedReport(info, List.copyOf(rows), List.copyOf(warnings));
    }

    private static ReportRow row(XMLStreamReader xml, String component, String file, int n, List<String> warnings) {
        String id = attr(xml, "cve_id");
        String summary = attr(xml, "cve_summary");
        String location = component + (file == null ? "" : " (" + file + ")");
        String cve = id != null && CVE.matcher(id).matches() ? id.toUpperCase(Locale.ROOT) : null;
        // no CVE (a SRCCLR-SID-... advisory): the advisory id is the title - short, and what a Jira summary keeps
        String title = cve != null ? firstSentence(summary) : id != null ? id : firstSentence(summary);
        if (title == null) {
            title = cve;
        }
        if (cve == null && title == null) {
            warnings.add(location + ": vulnerability " + n + " skipped, neither a CVE nor an advisory id or summary");
            return null;
        }
        return new ReportRow(location, cve, title, component, severity(xml), cvss(xml, location, warnings), null,
                null, summary);
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

    static String firstSentence(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.strip().replaceAll("\\s+", " ");
        var end = SENTENCE_END.matcher(t);
        if (end.find()) {
            t = t.substring(0, end.start());
        }
        return t.length() <= TITLE_MAX ? t : t.substring(0, TITLE_MAX - 1).strip() + "…";
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
