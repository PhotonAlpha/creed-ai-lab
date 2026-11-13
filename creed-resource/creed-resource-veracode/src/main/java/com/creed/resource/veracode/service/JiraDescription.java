package com.creed.resource.veracode.service;

import com.creed.resource.veracode.api.dto.SastIssueView;
import com.creed.resource.veracode.domain.ApplicationAliases;
import com.creed.resource.veracode.domain.Finding;
import com.creed.resource.veracode.domain.SastFlaw;
import com.creed.resource.veracode.domain.SastIssue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A created issue's description, in Jira wiki markup (the v2 API's format). */
final class JiraDescription {

    private JiraDescription() {
    }

    /**
     * One table, the page's columns in the page's order (the title's {@code cve_id|cwe_id} as two rows); the
     * {@code cve_summary} is its last row, in full. The application is shown by its alias, linked
     * (application aliases).
     */
    static String of(Finding f, ReportView report, ApplicationAliases apps) {
        StringBuilder d = new StringBuilder(SCA_HEADING).append('\n');
        d.append("||Field||Value||\n");
        // not one "cve|cwe" cell: | ends a cell, and Jira Cloud renders an escaped \| as a backslash and a new cell
        row(d, "CVE", f.cve() != null
                ? "[" + f.cve() + "|https://nvd.nist.gov/vuln/detail/" + f.cve() + "]" : escape(f.cveText()));
        row(d, "CWE", cwe(f));
        row(d, "Veracode application", application(f.application(), apps));
        row(d, "Library file", escape(f.fileName()));
        row(d, "Component(s)", escape(String.join(", ", f.components())));
        row(d, "Severity", escape(f.severity()));
        row(d, "CVSS", f.cvss() == null ? "" : f.cvss().toString());
        row(d, "Affected version", escape(f.affectedVersion()));
        row(d, "Location", escape(String.join("; ", f.locations())));
        row(d, "Description", escape(f.description()));
        d.append("\nSource: ").append(source(report)).append("\n");
        return d.toString();
    }

    static final String SCA_HEADING = "h3. Veracode SCA finding";
    static final String SAST_HEADING = "h3. Veracode SAST finding";
    private static final String SAST_FLAWS = "||Application||Severity||File||Line||Module||Issue ID||First found||";
    /** {@code |Veracode application|<cell>|}: the application an SCA table is for. */
    private static final Pattern SCA_APPLICATION = Pattern.compile("(?m)^\\|Veracode application\\|(.*)\\|$");
    /** A flaw row's first cell - a link {@code [name|url]} holds a {@code |} of its own. */
    private static final Pattern FIRST_CELL = Pattern.compile("^\\|(\\[[^\\]]*]|[^|]*)\\|");

    /**
     * An existing issue's description once another application's finding of the same CVE + library is labelled onto
     * it (asked for, 2026-11-13): one {@link #of} table per application. The findings' applications get a new table
     * (in place of their old one, if any); the old description's tables of other applications - filed from an earlier
     * analysis, not in this one - are kept as they were, in their order, the new ones after them. Anything else in it
     * (text before the first table, a table naming no application) is dropped: the description is the tool's.
     *
     * @param current the issue's description, {@code null} for none
     */
    static String mergeSca(String current, List<Finding> findings, Function<Finding, ReportView> reportOf,
                           ApplicationAliases apps) {
        Map<String, String> fresh = new LinkedHashMap<>();
        for (Finding f : findings) {
            fresh.putIfAbsent(displayName(application(f.application(), apps)), of(f, reportOf.apply(f), apps));
        }
        List<String> tables = new ArrayList<>();
        for (String old : split(current, SCA_HEADING)) {
            Matcher m = SCA_APPLICATION.matcher(old);
            String name = m.find() ? displayName(m.group(1)) : null;
            if (name == null || name.isBlank()) {
                continue;
            }
            String replaced = fresh.remove(name);
            tables.add(replaced != null ? replaced : old.stripTrailing() + "\n");
        }
        tables.addAll(fresh.values());
        return String.join("\n", tables);
    }

    /** The parts of {@code text} that start with {@code heading}, each with it; what comes before the first is not one. */
    private static List<String> split(String text, String heading) {
        List<String> parts = new ArrayList<>();
        if (text == null) {
            return parts;
        }
        int at = text.indexOf(heading);
        while (at >= 0) {
            int next = text.indexOf(heading, at + heading.length());
            parts.add(text.substring(at, next < 0 ? text.length() : next));
            at = next;
        }
        return parts;
    }

    /** {@code [Creed-Gateway|https://...]} -> {@code Creed-Gateway}; plain text as it is (both escaped as written). */
    static String displayName(String cell) {
        if (cell == null) {
            return null;
        }
        String text = cell.trim();
        if (text.startsWith("[") && text.endsWith("]") && text.indexOf('|') > 0) {
            return text.substring(1, text.indexOf('|'));
        }
        return text;
    }

    /**
     * Where the report came from (asked for, 2026-11-10): a Confluence attachment is linked by its URL, and the page
     * holding it too (asked for, 2026-11-10) - the attachment's own page is where its context lives; an uploaded
     * file is attached to the issue itself, after it is created. Then the report inside it, the scan and the policy.
     */
    static String source(ReportView report) {
        StringBuilder d = new StringBuilder();
        String name = report.sourceName() != null ? report.sourceName() : report.fileName();
        if (report.sourceUrl() != null) {
            d.append("report Confluence attachment [").append(escape(name)).append('|').append(report.sourceUrl()).append(']');
            if (report.sourcePageUrl() != null) {
                String page = report.sourcePageTitle() == null || report.sourcePageTitle().isBlank()
                        ? report.sourcePageUrl() : report.sourcePageTitle();
                d.append(" on page [").append(escape(page)).append('|').append(report.sourcePageUrl()).append(']');
            }
        } else {
            d.append("uploaded report ").append(escape(name)).append(" (attached to this issue)");
        }
        if (report.fileName() != null && !report.fileName().equals(name)) {
            d.append(", report ").append(escape(report.fileName()));
        }
        if (report.scanVersion() != null) {
            d.append(", scan ").append(escape(report.scanVersion()));
        }
        if (report.policyName() != null) {
            d.append(", policy ").append(escape(report.policyName()));
        }
        return d.toString();
    }

    /** The alias, a link when it has a repository URL; the {@code app_name} as text otherwise. */
    static String application(String appName, ApplicationAliases apps) {
        String name = apps.name(appName);
        String url = apps.url(appName);
        return name == null ? null : url == null ? escape(name) : "[" + escape(name) + "|" + url + "]";
    }

    /**
     * A SAST issue: the CWE and category, then one row per open flaw (application, severity, file:line, module, Veracode
     * issue id, first found), the first flaw's description - Veracode words it per CWE, so the flaws share it - and
     * the source of every report among the issue's applications.
     *
     * @param project {@code sast.project-name}: the head's {@code <name>: <project> - <category>} line
     */
    static String ofSast(SastIssue issue, List<ReportView> reports, ApplicationAliases apps, String project) {
        // first what the page's "Jira summary" column shows (asked for, 2026-11-12) - three lines per application:
        //   Creed-CCS: IDB VERACODE STSA - OS Command Injection / CWE 78 <name> / CWE 78 (Flaw IDs: 2)
        // the summary itself stays one line: it is what the issue is found by
        StringBuilder d = new StringBuilder(sastHead(issue, project, apps));
        d.append(SAST_HEADING).append('\n');
        d.append("||Field||Value||\n");
        row(d, "CWE", issue.cwe() != null && issue.cwe().matches("CWE-\\d+")
                ? "[" + issue.cwe() + "|https://cwe.mitre.org/data/definitions/" + issue.cwe().substring(4) + ".html]"
                : escape(issue.cweText()));
        row(d, "CWE name", escape(issue.cweName()));
        row(d, "Category", escape(issue.categoryText()));
        row(d, "Highest severity", escape(issue.severity()));
        row(d, "Veracode application(s)", String.join(", ",
                issue.applications().stream().map(a -> application(a, apps)).toList()));
        row(d, "Open flaws", String.valueOf(issue.flaws().size()));
        d.append('\n').append(SAST_FLAWS).append('\n');
        for (SastFlaw f : issue.flaws()) {
            String application = application(f.application(), apps);
            d.append('|').append(application == null ? " " : application).append('|').append(cell(f.severity()))
                    .append('|').append(cell(f.sourceFile())).append('|').append(f.line() == null ? " " : f.line())
                    .append('|').append(cell(f.module())).append('|').append(cell(f.issueId()))
                    .append('|').append(cell(f.firstFound())).append("|\n");
        }
        String description = issue.flaws().stream().map(SastFlaw::description)
                .filter(t -> t != null && !t.isBlank()).findFirst().orElse(null);
        if (description != null) {
            d.append("\nh4. Description\n").append(escape(description)).append('\n');
        }
        // each line names its application: relabelling keeps the lines of applications the analysis does not hold
        Set<String> sources = new LinkedHashSet<>();
        reports.stream().filter(r -> issue.applications().contains(r.appName()))
                .forEach(r -> sources.add("Source (" + application(r.appName(), apps) + "): " + source(r)));
        if (!sources.isEmpty()) {
            d.append('\n');
            sources.forEach(line -> d.append(line).append('\n'));
        }
        return d.toString();
    }

    /**
     * An existing SAST issue's description once another application's flaws are labelled onto it (asked for,
     * 2026-11-13): {@link #ofSast} for this analysis, with what the old description holds of applications this analysis
     * does not - their head blocks, their {@code Veracode application(s)} entries, their flaw rows, their
     * {@code Source (<application>)} lines - kept. The open-flaw count and the highest severity then count the kept rows too. An old description this
     * service did not write (no {@value #SAST_HEADING} table) has nothing to keep.
     *
     * @param current the issue's description, {@code null} for none
     */
    static String mergeSast(String current, SastIssue issue, List<ReportView> reports, ApplicationAliases apps,
                            String project) {
        String fresh = ofSast(issue, reports, apps, project);
        int table = current == null ? -1 : current.indexOf(SAST_HEADING);
        if (table < 0) {
            return fresh;
        }
        Set<String> names = new HashSet<>();
        issue.applications().forEach(a -> names.add(displayName(application(a, apps))));

        List<String> heads = new ArrayList<>();
        for (String block : current.substring(0, table).split("\n\\s*\n")) {
            String first = block.strip().lines().findFirst().orElse("");
            int colon = first.startsWith("[") ? first.indexOf("]: ") + 1 : first.indexOf(": ");
            if (colon > 0 && !names.contains(displayName(first.substring(0, colon)))) {
                heads.add(block.strip());
            }
        }
        List<String> entries = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        boolean inFlaws = false;
        for (String line : current.substring(table).lines().toList()) {
            if (line.startsWith("|Veracode application(s)|")) {
                String cell = line.substring("|Veracode application(s)|".length(), line.length() - 1);
                for (String entry : cell.split(", ")) {
                    if (!entry.isBlank() && !names.contains(displayName(entry))) {
                        entries.add(entry.trim());
                    }
                }
            } else if (line.equals(SAST_FLAWS)) {
                inFlaws = true;
            } else if (inFlaws && line.startsWith("|")) {
                Matcher m = FIRST_CELL.matcher(line);
                if (m.find() && !names.contains(displayName(m.group(1)))) {
                    rows.add(line);
                }
            } else {
                inFlaws = false;
                String source = sourceApplication(line);
                if (source != null && !names.contains(source)) {
                    sources.add(line);
                }
            }
        }

        String head = fresh.substring(0, fresh.indexOf(SAST_HEADING));
        StringBuilder d = new StringBuilder(head);
        heads.forEach(h -> d.append(h).append("\n\n"));
        String severity = highest(issue.severity(), rows);
        List<String> out = new ArrayList<>(fresh.substring(head.length()).lines().toList());
        int lastRow = -1;
        for (int i = 0; i < out.size(); i++) {
            String line = out.get(i);
            if (line.startsWith("|Veracode application(s)|") && !entries.isEmpty()) {
                out.set(i, line.substring(0, line.length() - 1) + ", " + String.join(", ", entries) + "|");
            } else if (line.startsWith("|Open flaws|")) {
                out.set(i, "|Open flaws|" + (issue.flaws().size() + rows.size()) + "|");
            } else if (line.startsWith("|Highest severity|")) {
                out.set(i, "|Highest severity|" + (severity == null ? " " : escape(severity)) + "|");
            } else if (line.equals(SAST_FLAWS) || line.startsWith("|") && i - 1 == lastRow) {
                // the flaw table: its header, then each row - the kept rows go after the last
                lastRow = i;
            }
        }
        if (lastRow >= 0) {
            out.addAll(lastRow + 1, rows);
        }
        sources.stream().filter(l -> !out.contains(l)).distinct().forEach(out::add);
        out.forEach(line -> d.append(line).append('\n'));
        return d.toString();
    }

    /**
     * {@code Source (<application>): ...} -> the application's name; {@code null} for any other line - a {@code Source:}
     * line written before the lines named their application (2026-11-13) cannot be told apart, so it is not kept.
     */
    private static String sourceApplication(String line) {
        if (!line.startsWith("Source (")) {
            return null;
        }
        String rest = line.substring("Source (".length());
        int end = rest.startsWith("[") ? rest.indexOf("]): ") + 1 : rest.indexOf("): ");
        return end <= 0 ? null : displayName(rest.substring(0, end));
    }

    /** The highest of the issue's severity and the kept rows' (their second cell), Veracode's order. */
    private static String highest(String severity, List<String> rows) {
        String best = severity;
        for (String row : rows) {
            Matcher m = FIRST_CELL.matcher(row);
            if (!m.find()) {
                continue;
            }
            String rest = row.substring(m.end());
            String level = rest.substring(0, Math.max(rest.indexOf('|'), 0)).trim();
            if (rank(level) < rank(best)) {
                best = level;
            }
        }
        return best;
    }

    private static int rank(String severity) {
        int i = severity == null ? -1 : ScaSummary.LEVELS.stream().map(String::toLowerCase).toList()
                .indexOf(severity.toLowerCase(Locale.ROOT));
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** What the page's "Jira summary" column shows, three lines per application. */
    private static String sastHead(SastIssue issue, String project, ApplicationAliases apps) {
        StringBuilder d = new StringBuilder();
        for (SastIssueView.ApplicationBlock block : SastIssueView.blocks(issue, project, apps)) {
            d.append(block.url() == null ? escape(block.name()) : "[" + escape(block.name()) + "|" + block.url() + "]")
                    .append(": ").append(escape(block.heading())).append('\n')
                    .append(escape(block.cwe())).append('\n')
                    .append(escape(block.flawIds())).append("\n\n");
        }
        return d.toString();
    }

    private static String cell(String value) {
        return value == null || value.isBlank() ? " " : escape(value.replaceAll("\\s*\\R\\s*", " "));
    }

    /** {@code CWE-502} links to MITRE's entry; "Unknown" stays text. */
    private static String cwe(Finding f) {
        if (f.cwe() != null && f.cwe().matches("CWE-\\d+")) {
            return "[" + f.cwe() + "|https://cwe.mitre.org/data/definitions/" + f.cwe().substring(4) + ".html]";
        }
        return escape(f.cweText());
    }

    private static void row(StringBuilder d, String field, String value) {
        // a line break would end the table row: a multi-line summary is kept to one
        String cell = value == null || value.isBlank() ? " " : value.replaceAll("\\s*\\R\\s*", " ");
        d.append('|').append(field).append('|').append(cell).append("|\n");
    }

    /**
     * {@code [ ] { }} start links/macros in wiki markup: escaped. {@code |} ends a table cell, and Jira Cloud does not
     * honour {@code \|} in a cell (it shows the backslash and splits the cell): it becomes {@code ¦}, which reads alike.
     */
    private static String escape(String text) {
        return text == null ? null : text.replace("|", "¦").replaceAll("([\\[\\]{}])", "\\\\$1");
    }
}
