package com.creed.resource.veracode.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory Jira project {@code CREED}. Seeded so the sample report shows every outcome: one CVE filed
 * for the analysed version, one filed only for an older version (still missing for 2.3.0), one filed
 * in free wording (still found - matching is on the CVE token and the component's words), and a CVE whose
 * id is a prefix of another's. Every seed names its component file as the summary template writes it
 * ({@code log4j-core-2.14.1.jar}): with {@code {file}} in the template a summary without it is not a match. Seeds
 * carry the sample application's label, so they do not read as "label missing".
 * Created issues are lost on restart.
 *
 * <p>The seeded issues carry a priority and an assignee (one unassigned) so the page's Jira filters have
 * something to filter; a created one is "To Do", unassigned, reported by the token's user.
 */
@Component
@ConditionalOnProperty(name = "veracode.mock.enabled", havingValue = "true")
public class MockJiraStore {

    public static final String PROJECT = "CREED";
    /** The sample report's application ({@code creed-platform-2026-10-06-Creed}) as a label: after the second hyphen. */
    public static final String SAMPLE_APPLICATION_LABEL = "2026-10-06-Creed";

    /** @param assignee {@code null} = unassigned; @param reporter who created it ({@code null} for seeds) */
    public record Issue(int id, String key, String summary, String description, String status, String priority,
                        String assignee, String reporter, List<String> versions, List<String> components,
                        String environment, List<String> labels, String issueType) {
    }

    private final List<String> versions = List.of("2.2.1", "2.3.0", "2.4.0");
    private final List<Issue> issues = new ArrayList<>();
    private final AtomicInteger sequence = new AtomicInteger();

    public MockJiraStore() {
        reset();
    }

    public synchronized void reset() {
        issues.clear();
        sequence.set(100);
        seed("creed - CVE-2021-44228|CWE-502 - log4j-core-2.14.1.jar",
                "In Progress", "Highest", "Alice Wang", "2.3.0");
        seed("creed - CVE-2022-22965|CWE-94 - spring-beans-5.3.17.jar", "Done", "High", "Bob Li", "2.2.1");
        // separators instead of the id's own hyphens: still found (the title match is loose)
        seed("Upgrade jackson-databind-2.12.6.jar: cve 2020 36518 deep wrapper array nesting", "To Do", "Medium", null, "2.3.0");
        seed("[Veracode] CVE-2021-4104 JMSAppender deserialization in log4j-1.2.17.jar", "Done", "High", "Alice Wang", "2.3.0");
    }

    private void seed(String summary, String status, String priority, String assignee, String version) {
        int n = sequence.incrementAndGet();
        issues.add(new Issue(10_000 + n, PROJECT + "-" + n, summary, null, status, priority, assignee, null,
                List.of(version), List.of("Security"), "Production", List.of("veracode", SAMPLE_APPLICATION_LABEL), "Bug"));
    }

    public List<String> versions() {
        return versions;
    }

    public synchronized List<Issue> all() {
        return List.copyOf(issues);
    }

    public synchronized Issue create(String summary, String description, List<String> versions, List<String> components,
                                     String environment, List<String> labels, String reporter, String issueType) {
        int n = sequence.incrementAndGet();
        Issue issue = new Issue(10_000 + n, PROJECT + "-" + n, summary, description, "To Do", "Medium", null,
                reporter, List.copyOf(versions), List.copyOf(components), environment, List.copyOf(labels), issueType);
        issues.add(issue);
        return issue;
    }

    /** {@code update.labels.add}: the label appended unless present, case ignored as Jira does. */
    public synchronized Issue addLabel(String key, String label) {
        for (int i = 0; i < issues.size(); i++) {
            Issue it = issues.get(i);
            if (it.key().equalsIgnoreCase(key)) {
                List<String> labels = new ArrayList<>(it.labels());
                if (labels.stream().noneMatch(label::equalsIgnoreCase)) {
                    labels.add(label);
                }
                Issue updated = new Issue(it.id(), it.key(), it.summary(), it.description(), it.status(), it.priority(),
                        it.assignee(), it.reporter(), it.versions(), it.components(), it.environment(),
                        List.copyOf(labels), it.issueType());
                issues.set(i, updated);
                return updated;
            }
        }
        return null;
    }

    public synchronized Issue find(String key) {
        return issues.stream().filter(i -> i.key().equalsIgnoreCase(key)).findFirst().orElse(null);
    }
}
