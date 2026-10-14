package com.creed.simple.tutorial.openapi;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Publishes one OpenAPI document per group.
 *
 * <ul>
 *   <li>{@code GET /openapi} - the group index, so the available groups can be discovered</li>
 *   <li>{@code GET /openapi/{group}.yaml} - the group's filtered specification</li>
 * </ul>
 *
 * <p>These are the URLs the Swagger UI definition dropdown points at; see
 * {@link SwaggerUiGroupConfig}.
 */
@RestController
@RequestMapping("/openapi")
public class OpenApiGroupController {

    private final OpenApiGroupService service;

    public OpenApiGroupController(OpenApiGroupService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> index(HttpServletRequest request) {
        // Absolute path including the servlet context (/simple), like the URLs springdoc puts in the dropdown
        String base = request.getContextPath() + "/openapi/";
        return service.groups().stream()
                .map(g -> Map.<String, Object>of(
                        "name", g.getName(),
                        "displayName", g.getDisplayName(),
                        "tags", g.getTags(),
                        "url", base + g.getName() + ".yaml"))
                .toList();
    }

    @GetMapping(value = "/{group}.yaml", produces = "application/yaml;charset=UTF-8")
    public ResponseEntity<String> spec(@PathVariable String group) {
        return service.findGroup(group)
                .map(g -> ResponseEntity.ok(service.render(g)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
