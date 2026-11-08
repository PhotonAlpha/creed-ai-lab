package com.creed.resource.veracode.service;

import com.creed.resource.veracode.domain.ReportRow;
import com.creed.resource.veracode.domain.SastFlaw;
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
 * {@code https://www.veracode.com/schema/reports/export/1.0}) reduced to its SCA findings - one
 * {@link ReportRow} per {@code software_composition_analysis/vulnerable_components/component/vulnerabilities/vulnerability}
 * - and its SAST flaws, one {@link SastFlaw} per {@code severity/category/cwe/staticflaws/flaw} (filed in Jira per CWE +
 * category as {@link com.creed.resource.veracode.domain.SastIssue}s).
 *
 * <p>The application's version - Jira's "Affects Version/s" - is the root's {@code version} (the scan / build
 * name), not the component's: {@code component/@version} is the <em>library's</em> version and goes into
 * the component label ({@code log4j-core 2.14.1}). The page can override it when the scan name is not a
 * release ({@code "16 Oct 2026 Static"}).
 *
 * <p>Streamed with StAX, not a DOM: a detailed report can run to tens of megabytes (every static flaw carries its
 * description), and only the attributes are read - no element text, recommendations or annotations. Elements are matched by local name, so the namespace
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
    public record ParsedReport(ReportInfo info, List<ReportRow> rows, List<String> warnings, ScaSummary sca,
                               List<SastFlaw> sastFlaws, SastSummary sast) {

        public ParsedReport withVersion(String affectedVersion) {
            return new ParsedReport(info, rows.stream().map(r -> r.withVersion(affectedVersion)).toList(), warnings,
                    sca, sastFlaws, sast);
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
        // SAST: the static analysis' rating, the root's own flaw counts, and the flaws with their <cwe>'s name
        String rating = null;
        Integer score = null;
        Integer totalFlaws = null;
        Integer flawsNotMitigated = null;
        String cweName = null;
        List<SastFlaw> flaws = new ArrayList<>();
        while (xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.END_ELEMENT) {
                String ended = path.pop();
                if ("component".equals(ended)) {
                    component = null;
                    filePath = null;
                } else if ("cwe".equals(ended)) {
                    cweName = null;
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
                totalFlaws = count(xml, name, "total_flaws", warnings);
                flawsNotMitigated = count(xml, name, "flaws_not_mitigated", warnings);
                continue;
            }
            if ("static-analysis".equals(name)) {
                rating = attr(xml, "rating");
                score = count(xml, name, "score", warnings);
            } else if ("cwe".equals(name)) {
                cweName = attr(xml, "cwename");
            } else if ("flaw".equals(name) && "staticflaws".equals(parent)) {
                flaws.add(flaw(xml, application, cweName, warnings));
            } else if ("software_composition_analysis".equals(name)) {
                thirdParty = count(xml, name, "third_party_components", warnings);
                String violate = attr(xml, "violate_policy");
                violatesPolicy = violate == null ? null : Boolean.valueOf(violate);
                violatedPolicy = count(xml, name, "components_violated_policy", warnings);
                blacklisted = count(xml, name, "blacklisted_components", warnings);
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
        return new ParsedReport(info, List.copyOf(rows), List.copyOf(warnings), sca, List.copyOf(flaws),
                sastSummary(rating, score, totalFlaws, flawsNotMitigated, flaws));
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

    private static SastFlaw flaw(XMLStreamReader xml, String application, String cweName, List<String> warnings) {
        String cweId = attr(xml, "cweid");
        String path = attr(xml, "sourcefilepath");
        String file = attr(xml, "sourcefile");
        String sourceFile = file == null ? path : path == null ? file : path.endsWith("/") ? path + file : path + "/" + file;
        String remediation = attr(xml, "remediation_status");
        String mitigation = attr(xml, "mitigation_status");
        String policy = attr(xml, "affects_policy_compliance");
        return new SastFlaw(application, attr(xml, "issueid"), severity(xml),
                cweId == null ? null : "CWE-" + cweId.replaceFirst("(?i)^cwe-", ""), cweName,
                attr(xml, "categoryname"), attr(xml, "module"), sourceFile,
                count(xml, "flaw " + attr(xml, "issueid"), "line", warnings), attr(xml, "scope"), remediation,
                attr(xml, "mitigation_status_desc") != null ? attr(xml, "mitigation_status_desc") : mitigation,
                "accepted".equalsIgnoreCase(mitigation), "fixed".equalsIgnoreCase(remediation),
                policy == null ? null : Boolean.valueOf(policy), attr(xml, "date_first_occurrence"),
                attr(xml, "description"));
    }

    private static SastSummary sastSummary(String rating, Integer score, Integer totalFlaws, Integer flawsNotMitigated,
                                           List<SastFlaw> flaws) {
        int[] bySeverity = new int[ScaSummary.LEVELS.size()];
        int open = 0, mitigated = 0, fixed = 0, affectsPolicy = 0, unknown = 0;
        for (SastFlaw f : flaws) {
            if (f.fixed()) {
                fixed++;
            } else if (f.mitigated()) {
                mitigated++;
            } else {
                open++;
                if (Boolean.TRUE.equals(f.affectsPolicy())) {
                    affectsPolicy++;
                }
                int rank = Severity.rank(f.severity());
                if (rank >= 0) {
                    bySeverity[ScaSummary.LEVELS.size() - 1 - rank]++;
                } else {
                    unknown++;
                }
            }
        }
        List<ScaSummary.SeverityCount> levels = new ArrayList<>();
        for (int i = 0; i < bySeverity.length; i++) {
            levels.add(new ScaSummary.SeverityCount(ScaSummary.LEVELS.get(i), bySeverity[i]));
        }
        return new SastSummary(rating, score, totalFlaws, flawsNotMitigated, flaws.size(), open, mitigated, fixed,
                affectsPolicy, List.copyOf(levels), unknown);
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
    private static Integer count(XMLStreamReader xml, String element, String name, List<String> warnings) {
        String raw = attr(xml, name);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            warnings.add(element + ": " + name + " '" + raw + "' is not a number, left empty");
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
