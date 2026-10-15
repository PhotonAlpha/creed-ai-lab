package com.creed.resource.veracode.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stand-in for Confluence's {@code GET /rest/api/content/{id}}: the page body is
 * {@code classpath:mock/confluence/<id>.html}, written in storage format like a page saved by the
 * Confluence editor ({@code <th>} headers inside {@code <tbody>}, {@code <p>} in every cell, a Status
 * macro, a table that is not the report).
 *
 * <p>Edit the HTML file and re-analyse to try a report change; the version number is fixed.
 */
@RestController
@RequestMapping("/mock/confluence")
@ConditionalOnProperty(name = "veracode.mock.enabled", havingValue = "true")
public class MockConfluenceController {

    static final Map<String, String> PAGES = Map.of(
            "98765", "Veracode SCA Report - creed-platform 2.3.0",
            "98766", "Release sign-off - creed-platform 2.3.0");

    @GetMapping("/rest/api/content/{id}")
    ResponseEntity<Map<String, Object>> content(@PathVariable String id,
                                                @RequestParam(defaultValue = "") String expand) {
        String title = PAGES.get(id);
        if (title == null) {
            // Confluence's own 404 shape.
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "statusCode", 404, "message", "No content found with id: ContentId{id=" + id + "}"));
        }
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("id", id);
        page.put("type", "page");
        page.put("title", title);
        page.put("version", Map.of("number", 7, "when", "2026-10-14T08:30:00.000Z"));
        if (expand.contains("body.storage")) {
            page.put("body", Map.of("storage", Map.of("value", storage(id), "representation", "storage")));
        }
        page.put("_links", Map.of(
                "base", ServletUriComponentsBuilder.fromCurrentContextPath().path("/mock/confluence").toUriString(),
                "webui", "/pages/viewpage.action?pageId=" + id));
        return ResponseEntity.ok(page);
    }

    /** What the page link in the UI opens: the storage body, unstyled. */
    @GetMapping(value = "/pages/viewpage.action", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> view(@RequestParam String pageId) {
        String title = PAGES.get(pageId);
        if (title == null) {
            return ResponseEntity.notFound().build();
        }
        String html = "<!doctype html><meta charset=\"utf-8\"><title>" + HtmlUtils.htmlEscape(title)
                + "</title><style>table{border-collapse:collapse}td,th{border:1px solid #999;padding:4px}</style>"
                + "<p><i>mock Confluence</i></p><h1>" + HtmlUtils.htmlEscape(title) + "</h1>" + storage(pageId);
        return ResponseEntity.ok(html);
    }

    private static String storage(String id) {
        try {
            return new ClassPathResource("mock/confluence/" + id + ".html").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
