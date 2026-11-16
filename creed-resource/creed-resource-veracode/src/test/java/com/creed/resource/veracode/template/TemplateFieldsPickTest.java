package com.creed.resource.veracode.template;

import com.creed.resource.veracode.service.JiraLookupService.IssueRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** One Jira issue per template row (asked for, 2026-11-16): the chosen one, else the oldest open one. */
class TemplateFieldsPickTest {

    private static final List<String> CLOSED = List.of("Closed", "Done");

    private static IssueRef issue(String key, String status) {
        return new IssueRef(key, "summary", status, null, null, List.of(), List.of(), "https://jira/browse/" + key);
    }

    private static List<String> keys(List<IssueRef> issues) {
        return issues.stream().map(IssueRef::key).toList();
    }

    @Test
    void noneOrOneIsLeftAsItIs() {
        assertThat(TemplateFields.pick(List.of(), "CREED-1", CLOSED)).isEmpty();
        assertThat(keys(TemplateFields.pick(List.of(issue("CREED-7", "Done")), "CREED-1", CLOSED)))
                .containsExactly("CREED-7");
    }

    @Test
    void theChosenOneWinsEvenWhenClosed() {
        List<IssueRef> issues = List.of(issue("CREED-10", "To Do"), issue("CREED-9", "Done"));
        assertThat(keys(TemplateFields.pick(issues, "creed-9", CLOSED))).containsExactly("CREED-9");
    }

    @Test
    void withoutAChoiceTheOldestOpenOne() {
        List<IssueRef> issues = List.of(issue("CREED-12", "To Do"), issue("CREED-3", " done "),
                issue("CREED-10", "In Progress"), issue("CREED-9", null));
        // CREED-3 is closed; among the open ones 9 comes before 10 (by number, not text)
        assertThat(keys(TemplateFields.pick(issues, null, CLOSED))).containsExactly("CREED-9");
        // a choice no longer among them (Jira is read afresh at export) = the default
        assertThat(keys(TemplateFields.pick(issues, "CREED-99", CLOSED))).containsExactly("CREED-9");
    }

    @Test
    void allClosedStillGivesOne() {
        List<IssueRef> issues = List.of(issue("CREED-20", "Closed"), issue("CREED-4", "Done"));
        assertThat(keys(TemplateFields.pick(issues, null, CLOSED))).containsExactly("CREED-4");
    }

    @Test
    void jiraLinksReadAsJiraKeys() {
        assertThat(new TemplateMapping.FieldCell(3, "jiraUrls").field()).isEqualTo("jiraKeys");
        assertThat(TemplateFields.known(TemplateFields.SCA, "jiraUrls")).isFalse();
        assertThat(TemplateFields.known(TemplateFields.SAST, "jiraKeys")).isTrue();
    }
}
