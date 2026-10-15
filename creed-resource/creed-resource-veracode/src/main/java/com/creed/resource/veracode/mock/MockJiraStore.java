package com.creed.resource.veracode.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory Jira project {@code CREED}. Seeded so the sample report shows every outcome: one CVE filed
 * for the analysed version, one filed only for an older version (still missing for 2.3.0), one filed
 * without the {@code [Veracode]} prefix (still found - matching is on the CVE token), and a CVE whose
 * id is a prefix of another's. Created issues are lost on restart.
 */
@Component
@ConditionalOnProperty(name = "veracode.mock.enabled", havingValue = "true")
public class MockJiraStore {

    public static final String PROJECT = "CREED";

    public record Issue(int id, String key, String summary, String description, String status,
                        List<String> versions, List<String> labels) {
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
        seed("[Veracode] CVE-2021-44228 Apache Log4j2 JNDI features do not protect against attacker-controlled LDAP",
                "In Progress", "2.3.0");
        seed("[Veracode] CVE-2022-22965 Spring Framework RCE via data binding on JDK 9+", "Done", "2.2.1");
        seed("Upgrade jackson-databind: CVE-2020-36518 deep wrapper array nesting", "To Do", "2.3.0");
        seed("[Veracode] CVE-2021-4104 Log4j 1.2 JMSAppender deserialization", "Done", "2.3.0");
    }

    private void seed(String summary, String status, String version) {
        int n = sequence.incrementAndGet();
        issues.add(new Issue(10_000 + n, PROJECT + "-" + n, summary, null, status, List.of(version),
                List.of("veracode")));
    }

    public List<String> versions() {
        return versions;
    }

    public synchronized List<Issue> all() {
        return List.copyOf(issues);
    }

    public synchronized Issue create(String summary, String description, List<String> versions, List<String> labels) {
        int n = sequence.incrementAndGet();
        Issue issue = new Issue(10_000 + n, PROJECT + "-" + n, summary, description, "To Do",
                List.copyOf(versions), List.copyOf(labels));
        issues.add(issue);
        return issue;
    }

    public synchronized Issue find(String key) {
        return issues.stream().filter(i -> i.key().equalsIgnoreCase(key)).findFirst().orElse(null);
    }
}
