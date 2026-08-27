package com.creed.resource.envmatrix.api;

import com.creed.resource.envmatrix.api.dto.BatchSaveRequest;
import com.creed.resource.envmatrix.api.dto.BatchSaveResponse;
import com.creed.resource.envmatrix.api.dto.ConflictGroup;
import com.creed.resource.envmatrix.api.dto.DimensionsResponse;
import com.creed.resource.envmatrix.api.dto.EndpointDto;
import com.creed.resource.envmatrix.api.dto.EndpointFilter;
import com.creed.resource.envmatrix.api.dto.EndpointRequest;
import com.creed.resource.envmatrix.api.dto.MatrixResponse;
import com.creed.resource.envmatrix.api.dto.ReleaseDto;
import com.creed.resource.envmatrix.api.dto.ReleaseRequest;
import com.creed.resource.envmatrix.api.dto.ReleaseStatus;
import com.creed.resource.envmatrix.api.dto.ReleaseTopologyDto;
import com.creed.resource.envmatrix.api.dto.ReleaseTopologyRequest;
import com.creed.resource.envmatrix.api.dto.ReleaseTopologySaveResponse;
import com.creed.resource.envmatrix.service.EnvMatrixService;
import com.creed.resource.envmatrix.service.HealthProbeService;
import com.creed.resource.envmatrix.service.ReleaseService;
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
    private final ReleaseService releaseService;

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
    // ------------------------------------------------------------ release topology

    /**
     * Releases — the named sets of environment slices the topology graph is scoped to.
     *
     * <p>A connection cannot be keyed on app systems: the chain
     * {@code SG CCS SIT3 -> Global-CCS SIT2 -> CN CCS SIT5} has CCS in it twice. So a topology node
     * is a slice, and a release is what says which slices belong together.
     */
    @GetMapping("/releases")
    public List<ReleaseDto> releases(@RequestParam(required = false) String tier,
                                     @RequestParam(required = false) ReleaseStatus status) {
        return releaseService.list(tier, status);
    }

    @GetMapping("/releases/{id}")
    public ReleaseDto release(@PathVariable Long id) {
        return releaseService.get(id);
    }

    @PostMapping("/releases")
    public ResponseEntity<ReleaseDto> createRelease(@Valid @RequestBody ReleaseRequest request) {
        return ResponseEntity.status(201).body(releaseService.create(request));
    }

    @PutMapping("/releases/{id}")
    public ReleaseDto updateRelease(@PathVariable Long id, @Valid @RequestBody ReleaseRequest request) {
        return releaseService.update(id, request);
    }

    /** Deletes the release and everything in it. */
    @DeleteMapping("/releases/{id}")
    public ResponseEntity<Void> deleteRelease(@PathVariable Long id) {
        releaseService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Participants and connections in one response — the graph's data source. */
    @GetMapping("/releases/{id}/topology")
    public ReleaseTopologyDto releaseTopology(@PathVariable Long id) {
        return releaseService.topology(id);
    }

    /**
     * Replaces one release's topology — the config page's save.
     *
     * <p>Answers {@code 422} with per-row {@code issues} when the payload is rejected, matching the
     * endpoint batch save so the UI can treat both the same way.
     */
    @PutMapping("/releases/{id}/topology")
    public ResponseEntity<ReleaseTopologySaveResponse> saveReleaseTopology(
            @PathVariable Long id, @Valid @RequestBody ReleaseTopologyRequest request) {
        ReleaseTopologySaveResponse response = releaseService.saveTopology(id, request);
        return response.success()
                ? ResponseEntity.ok(response)
                : ResponseEntity.unprocessableEntity().body(response);
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
