package com.creed.resource.envmatrix.api;

import com.creed.resource.envmatrix.api.dto.AppLinkDto;
import com.creed.resource.envmatrix.api.dto.AppLinkRequest;
import com.creed.resource.envmatrix.api.dto.BatchSaveRequest;
import com.creed.resource.envmatrix.api.dto.BatchSaveResponse;
import com.creed.resource.envmatrix.api.dto.ConflictGroup;
import com.creed.resource.envmatrix.api.dto.DimensionsResponse;
import com.creed.resource.envmatrix.api.dto.EndpointDto;
import com.creed.resource.envmatrix.api.dto.EndpointFilter;
import com.creed.resource.envmatrix.api.dto.EndpointRequest;
import com.creed.resource.envmatrix.api.dto.LinkBatchSaveRequest;
import com.creed.resource.envmatrix.api.dto.LinkBatchSaveResponse;
import com.creed.resource.envmatrix.api.dto.MatrixResponse;
import com.creed.resource.envmatrix.service.AppLinkService;
import com.creed.resource.envmatrix.service.EnvMatrixService;
import com.creed.resource.envmatrix.service.HealthProbeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Env Matrix Viewer API.
 *
 * <p>Every read route takes the same {@link EndpointFilter}, bound as a value object from repeated
 * query parameters — {@code ?tier=UAT&tier=SIT&scheme=https} — which maps one-to-one onto the UI's
 * multi-select filter bar. Filters are optional; an omitted dimension is left unconstrained.
 * {@code EnvMatrixControllerTest#filtersBindFromRepeatedQueryParameters} pins that binding down,
 * because a silently unbound filter would look like "filtering is broken" rather than an error.
 */
@RestController
@RequestMapping("/api/env-matrix")
@RequiredArgsConstructor
@Slf4j
public class EnvMatrixController {

    private final EnvMatrixService service;
    private final HealthProbeService healthProbeService;
    private final AppLinkService appLinkService;

    /** Liveness/echo, mirroring the other resource modules' {@code /ping}. */
    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of(
                "service", "creed-resource-env-matrix",
                "status", "UP",
                "healthProbeMode", healthProbeService.mode(),
                "time", Instant.now().toString());
    }

    /** Distinct dimension values, for the filter dropdowns and the config form's selects. */
    @GetMapping("/dimensions")
    public DimensionsResponse dimensions() {
        return service.dimensions();
    }

    /** Flat endpoint list — the config page's data source. */
    @GetMapping("/endpoints")
    public List<EndpointDto> endpoints(@ModelAttribute EndpointFilter filter) {
        return service.list(filter);
    }

    @GetMapping("/endpoints/{id}")
    public EndpointDto endpoint(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/endpoints")
    public ResponseEntity<EndpointDto> create(@Valid @RequestBody EndpointRequest request) {
        return ResponseEntity.status(201).body(service.create(request));
    }

    @PutMapping("/endpoints/{id}")
    public EndpointDto update(@PathVariable Long id, @Valid @RequestBody EndpointRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/endpoints/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Persist the whole edited table — the config page's save button. The requirement outline calls it
     * "save to file"; the store is the {@code env_matrix} database, so this writes back there.
     *
     * <p>Returns 422 with per-row issues when validation fails, and nothing is written.
     */
    @PutMapping("/endpoints")
    public ResponseEntity<BatchSaveResponse> batchSave(@Valid @RequestBody BatchSaveRequest request) {
        BatchSaveResponse response = service.batchSave(request);
        return response.success() ? ResponseEntity.ok(response) : ResponseEntity.unprocessableEntity().body(response);
    }

    /** {@code service × country} grid with conflict highlighting — the home page. */
    // ------------------------------------------------------------ app links (topology edges)

    /**
     * Declared app-system links for one tier — the topology graph's edges.
     *
     * <p>{@code tier} is optional here so the config editor can load everything at once, but the
     * topology page always passes one: the wiring is declared per tier, and an unscoped graph would
     * overlay four environments' topologies on top of each other.
     */
    @GetMapping("/links")
    public List<AppLinkDto> links(@RequestParam(required = false) String tier) {
        return appLinkService.list(tier);
    }

    @GetMapping("/links/{id}")
    public AppLinkDto link(@PathVariable Long id) {
        return appLinkService.get(id);
    }

    @PostMapping("/links")
    public ResponseEntity<AppLinkDto> createLink(@Valid @RequestBody AppLinkRequest request) {
        return ResponseEntity.status(201).body(appLinkService.create(request));
    }

    @PutMapping("/links/{id}")
    public AppLinkDto updateLink(@PathVariable Long id, @Valid @RequestBody AppLinkRequest request) {
        return appLinkService.update(id, request);
    }

    @DeleteMapping("/links/{id}")
    public ResponseEntity<Void> deleteLink(@PathVariable Long id) {
        appLinkService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Replaces one tier's wiring — the config page's link editor save.
     *
     * <p>Answers {@code 422} with per-row {@code issues} when the payload is rejected, matching the
     * endpoint batch save so the UI can treat both the same way.
     */
    @PutMapping("/links")
    public ResponseEntity<LinkBatchSaveResponse> batchSaveLinks(@Valid @RequestBody LinkBatchSaveRequest request) {
        LinkBatchSaveResponse response = appLinkService.batchSave(request);
        return response.success() ? ResponseEntity.ok(response) : ResponseEntity.unprocessableEntity().body(response);
    }

    @GetMapping("/matrix")
    public MatrixResponse matrix(@ModelAttribute EndpointFilter filter) {
        return service.matrix(filter);
    }

    /** Conflict groups only — powers the summary panel without re-fetching the grid. */
    @GetMapping("/conflicts")
    public List<ConflictGroup> conflicts(@ModelAttribute EndpointFilter filter) {
        return service.conflicts(filter);
    }

    /**
     * Health-probe metadata plus a per-endpoint state roll-up.
     *
     * <p>{@code mode} is {@code mock} unless {@code env-matrix.health.mode=real}; the UI surfaces it
     * verbatim so nobody mistakes mocked green ticks for a real reachability report.
     */
    @GetMapping("/health")
    public Map<String, Object> health(@ModelAttribute EndpointFilter filter) {
        List<EndpointDto> endpoints = service.list(filter);

        Map<String, Integer> summary = new LinkedHashMap<>();
        endpoints.forEach(e -> summary.merge(e.health().name(), 1, Integer::sum));

        Map<String, String> states = new LinkedHashMap<>();
        endpoints.forEach(e -> states.put(String.valueOf(e.id()), e.health().name()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", healthProbeService.mode());
        body.put("mocked", healthProbeService.isMocked());
        body.put("seed", healthProbeService.mockSeed());
        body.put("total", endpoints.size());
        body.put("summary", summary);
        body.put("states", states);
        body.put("checkedAt", Instant.now().toString());
        return body;
    }

    /**
     * Re-runs the health check. In {@code mock} mode this rotates the seed so the mocked states
     * visibly change; in {@code real} mode the states are recomputed by probing and the seed is
     * irrelevant.
     */
    @PostMapping("/health/recheck")
    public Map<String, Object> recheck() {
        long seed = healthProbeService.isMocked() ? healthProbeService.rotateMockSeed() : healthProbeService.mockSeed();
        log.info("health recheck requested (mode={})", healthProbeService.mode());
        return Map.of(
                "mode", healthProbeService.mode(),
                "mocked", healthProbeService.isMocked(),
                "seed", seed,
                "checkedAt", Instant.now().toString());
    }
}
