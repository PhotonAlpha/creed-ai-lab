package com.creed.resource.envmatrix.service;

import com.creed.resource.envmatrix.api.dto.BatchSaveRequest;
import com.creed.resource.envmatrix.api.dto.BatchSaveResponse;
import com.creed.resource.envmatrix.api.dto.ConflictGroup;
import com.creed.resource.envmatrix.api.dto.DimensionsResponse;
import com.creed.resource.envmatrix.api.dto.EndpointDto;
import com.creed.resource.envmatrix.api.dto.EndpointFilter;
import com.creed.resource.envmatrix.api.dto.EndpointRequest;
import com.creed.resource.envmatrix.api.dto.HealthState;
import com.creed.resource.envmatrix.api.dto.MatrixCell;
import com.creed.resource.envmatrix.api.dto.MatrixResponse;
import com.creed.resource.envmatrix.domain.EnvEndpoint;
import com.creed.resource.envmatrix.domain.EnvEndpointRepository;
import com.creed.resource.envmatrix.domain.EnvEndpointSpecifications;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Read and write side of the endpoint matrix.
 *
 * <p>Every read path follows the same three steps: fetch the filtered rows, run
 * {@link ConflictDetector} over exactly that set, probe health, then assemble DTOs. Doing conflict
 * detection on the filtered set (rather than on the whole table) is deliberate — the highlighting the
 * user sees always explains itself from the rows currently on screen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EnvMatrixService {

    /** Stable ordering so the matrix and the config table never reshuffle between requests. */
    private static final Sort DEFAULT_SORT = Sort.by(
            "appSystem", "tier", "envInstance", "service", "country", "instance", "scheme");

    private final EnvEndpointRepository repository;
    private final ConflictDetector conflictDetector;
    private final HealthProbeService healthProbeService;

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<EndpointDto> list(EndpointFilter filter) {
        List<EnvEndpoint> rows = fetch(filter);
        return toDtos(rows);
    }

    @Transactional(readOnly = true)
    public EndpointDto get(Long id) {
        EnvEndpoint endpoint = repository.findById(id).orElseThrow(() -> new EndpointNotFoundException(id));
        // Conflicts are a property of a *set*, so a single-row read is compared against the whole
        // table — otherwise a lone row could never be conflicting.
        ConflictDetector.Report report = conflictDetector.detect(repository.findAll());
        return EndpointDto.of(endpoint, report.isConflicting(id), report.keysFor(id),
                healthProbeService.probe(List.of(endpoint)).getOrDefault(id, HealthState.UNKNOWN));
    }

    @Transactional(readOnly = true)
    public DimensionsResponse dimensions() {
        return new DimensionsResponse(
                repository.findDistinctAppSystems(),
                repository.findDistinctTiers(),
                repository.findDistinctEnvInstances(),
                repository.findDistinctCountries(),
                repository.findDistinctServices(),
                repository.findDistinctInstances(),
                repository.findDistinctSchemes());
    }

    /**
     * Builds the {@code service × country} grid over the filtered set.
     *
     * <p>Row and column headers come from the filtered rows themselves, so filtering out an app
     * system also removes the services only it owns instead of leaving an empty band in the grid.
     */
    @Transactional(readOnly = true)
    public MatrixResponse matrix(EndpointFilter filter) {
        List<EnvEndpoint> rows = fetch(filter);
        ConflictDetector.Report report = conflictDetector.detect(rows);
        Map<Long, HealthState> health = healthProbeService.probe(rows);

        Set<String> services = new TreeSet<>();
        Set<String> countries = new TreeSet<>();
        Map<String, List<EndpointDto>> byCell = new LinkedHashMap<>();

        for (EnvEndpoint row : rows) {
            services.add(row.getService());
            countries.add(row.getCountry());
            byCell.computeIfAbsent(cellKey(row.getService(), row.getCountry()), k -> new ArrayList<>())
                    .add(EndpointDto.of(row, report.isConflicting(row.getId()), report.keysFor(row.getId()),
                            health.getOrDefault(row.getId(), HealthState.UNKNOWN)));
        }

        List<MatrixCell> cells = new ArrayList<>(byCell.size());
        byCell.forEach((key, endpoints) -> {
            int conflictCount = (int) endpoints.stream().filter(EndpointDto::conflict).count();
            cells.add(new MatrixCell(
                    endpoints.getFirst().service(),
                    endpoints.getFirst().country(),
                    endpoints,
                    conflictCount > 0,
                    conflictCount));
        });

        return new MatrixResponse(
                List.copyOf(services),
                List.copyOf(countries),
                cells,
                toConflictGroups(report, health),
                rows.size(),
                report.scope());
    }

    @Transactional(readOnly = true)
    public List<ConflictGroup> conflicts(EndpointFilter filter) {
        List<EnvEndpoint> rows = fetch(filter);
        ConflictDetector.Report report = conflictDetector.detect(rows);
        return toConflictGroups(report, healthProbeService.probe(rows));
    }

    // ----------------------------------------------------------------- writes

    @Transactional
    public EndpointDto create(EndpointRequest request) {
        requireUniqueDimensions(request, null);
        EnvEndpoint saved = repository.save(apply(new EnvEndpoint(), request));
        log.info("created endpoint id={} {}", saved.getId(), saved.hostPort());
        return decorateAgainstWholeTable(saved);
    }

    @Transactional
    public EndpointDto update(Long id, EndpointRequest request) {
        EnvEndpoint existing = repository.findById(id).orElseThrow(() -> new EndpointNotFoundException(id));
        requireUniqueDimensions(request, id);
        EnvEndpoint saved = repository.save(apply(existing, request));
        log.info("updated endpoint id={} {}", id, saved.hostPort());
        return decorateAgainstWholeTable(saved);
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new EndpointNotFoundException(id);
        }
        repository.deleteById(id);
        log.info("deleted endpoint id={}", id);
    }

    /**
     * Applies the whole edited table in one transaction — the config page's save action.
     *
     * <p>All rows are validated <em>before</em> anything is written, so a bad row cannot leave the
     * table half-saved. Duplicate dimension tuples are rejected; address conflicts are only
     * <em>reported</em> back, since recording a conflict you have just discovered is the entire point
     * of the tool and must not be blocked.
     */
    @Transactional
    public BatchSaveResponse batchSave(BatchSaveRequest request) {
        List<EndpointRequest> rows = request.endpoints();
        List<BatchSaveResponse.Issue> issues = validateBatch(rows);
        if (!issues.isEmpty()) {
            log.warn("batch save rejected with {} issue(s)", issues.size());
            return BatchSaveResponse.rejected(issues);
        }

        int inserted = 0;
        int updated = 0;
        List<Long> keptIds = new ArrayList<>();
        for (EndpointRequest row : rows) {
            if (row.id() == null) {
                EnvEndpoint saved = repository.save(apply(new EnvEndpoint(), row));
                keptIds.add(saved.getId());
                inserted++;
            } else {
                EnvEndpoint existing = repository.findById(row.id())
                        .orElseThrow(() -> new EndpointNotFoundException(row.id()));
                // The config page submits the whole table, so most rows are unchanged. Only count
                // (and touch) the ones that actually differ — otherwise the UI would report
                // "1235 updated" for a one-field edit, and every row's updatedAt would move even
                // though Hibernate's dirty checking correctly skips the write.
                if (differs(existing, row)) {
                    repository.save(apply(existing, row));
                    updated++;
                }
                keptIds.add(row.id());
            }
        }

        int deleted = 0;
        if (request.deleteMissing()) {
            List<EnvEndpoint> removable = repository.findAll().stream()
                    .filter(e -> !keptIds.contains(e.getId()))
                    .toList();
            repository.deleteAll(removable);
            deleted = removable.size();
        }

        List<EnvEndpoint> after = repository.findAll(DEFAULT_SORT);
        ConflictDetector.Report report = conflictDetector.detect(after);
        log.info("batch save applied inserted={} updated={} deleted={} conflicts={}",
                inserted, updated, deleted, report.groups().size());
        return new BatchSaveResponse(true, inserted, updated, deleted, List.of(),
                toConflictGroups(report, healthProbeService.probe(after)));
    }

    // ---------------------------------------------------------------- helpers

    private List<EnvEndpoint> fetch(EndpointFilter filter) {
        return repository.findAll(EnvEndpointSpecifications.matching(filter), DEFAULT_SORT);
    }

    private List<EndpointDto> toDtos(List<EnvEndpoint> rows) {
        ConflictDetector.Report report = conflictDetector.detect(rows);
        Map<Long, HealthState> health = healthProbeService.probe(rows);
        return rows.stream()
                .map(row -> EndpointDto.of(row, report.isConflicting(row.getId()), report.keysFor(row.getId()),
                        health.getOrDefault(row.getId(), HealthState.UNKNOWN)))
                .toList();
    }

    private EndpointDto decorateAgainstWholeTable(EnvEndpoint endpoint) {
        List<EnvEndpoint> all = repository.findAll();
        ConflictDetector.Report report = conflictDetector.detect(all);
        return EndpointDto.of(endpoint,
                report.isConflicting(endpoint.getId()),
                report.keysFor(endpoint.getId()),
                healthProbeService.probe(List.of(endpoint)).getOrDefault(endpoint.getId(), HealthState.UNKNOWN));
    }

    private List<ConflictGroup> toConflictGroups(ConflictDetector.Report report, Map<Long, HealthState> health) {
        return report.groups().stream()
                .map(group -> new ConflictGroup(group.kind(), group.scopeKey(), group.value(),
                        group.members().stream()
                                .map(member -> EndpointDto.of(member, true, report.keysFor(member.getId()),
                                        health.getOrDefault(member.getId(), HealthState.UNKNOWN)))
                                .toList()))
                .toList();
    }

    private static String cellKey(String service, String country) {
        return service + '|' + country;
    }

    /** Rejects a second row claiming the same seven-dimension identity. */
    private void requireUniqueDimensions(EndpointRequest request, Long selfId) {
        Optional<EnvEndpoint> clash = repository
                .findByAppSystemAndTierAndEnvInstanceAndCountryAndServiceAndInstanceAndScheme(
                        request.appSystem(), request.tier(), request.envInstance(), request.country(),
                        request.service(), request.instance(), request.scheme());
        if (clash.isPresent() && !clash.get().getId().equals(selfId)) {
            throw new DuplicateEndpointException(dimensionTuple(request), clash.get().getId());
        }
    }

    /**
     * Batch-level validation: catches duplicate identities <em>within the payload</em>, which the
     * per-row unique check cannot see because nothing has been flushed yet.
     */
    private List<BatchSaveResponse.Issue> validateBatch(List<EndpointRequest> rows) {
        List<BatchSaveResponse.Issue> issues = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            final int index = i;
            EndpointRequest row = rows.get(i);
            String tuple = dimensionTuple(row);
            Integer previous = seen.putIfAbsent(tuple, i);
            if (previous != null) {
                issues.add(new BatchSaveResponse.Issue(index, row.id(), "dimensions",
                        "duplicates row " + (previous + 1) + " (" + tuple + ")"));
                continue;
            }
            // A row whose identity already belongs to a *different* stored endpoint would violate
            // the unique index at flush time; report it as a row error instead.
            repository.findByAppSystemAndTierAndEnvInstanceAndCountryAndServiceAndInstanceAndScheme(
                            row.appSystem(), row.tier(), row.envInstance(), row.country(),
                            row.service(), row.instance(), row.scheme())
                    .filter(stored -> !stored.getId().equals(row.id()))
                    .ifPresent(stored -> issues.add(new BatchSaveResponse.Issue(index, row.id(), "dimensions",
                            "already used by endpoint #" + stored.getId() + " (" + tuple + ")")));
        }
        return issues;
    }

    /** True when the submitted row would actually change any stored field. */
    private static boolean differs(EnvEndpoint stored, EndpointRequest request) {
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        return !Objects.equals(stored.getAppSystem(), request.appSystem().trim())
                || !Objects.equals(stored.getTier(), request.tier().trim())
                || !Objects.equals(stored.getEnvInstance(), request.envInstance().trim())
                || !Objects.equals(stored.getCountry(), request.country().trim())
                || !Objects.equals(stored.getService(), request.service().trim())
                || !Objects.equals(stored.getInstance(), request.instance().trim())
                || !Objects.equals(stored.getScheme(), request.scheme().trim().toLowerCase())
                || !Objects.equals(stored.getHost(), request.host().trim())
                || !Objects.equals(stored.getIp(), request.ip().trim())
                || !Objects.equals(stored.getPort(), request.port())
                || !Objects.equals(stored.getNote(), note);
    }

    private static String dimensionTuple(EndpointRequest r) {
        return String.join("/", r.appSystem(), r.tier(), r.envInstance(), r.country(),
                r.service(), r.instance(), r.scheme());
    }

    private static EnvEndpoint apply(EnvEndpoint target, EndpointRequest request) {
        target.setAppSystem(request.appSystem().trim());
        target.setTier(request.tier().trim());
        target.setEnvInstance(request.envInstance().trim());
        target.setCountry(request.country().trim());
        target.setService(request.service().trim());
        target.setInstance(request.instance().trim());
        target.setScheme(request.scheme().trim().toLowerCase());
        target.setHost(request.host().trim());
        target.setIp(request.ip().trim());
        target.setPort(request.port());
        target.setNote(request.note() == null || request.note().isBlank() ? null : request.note().trim());
        return target;
    }

    /** 404 — no endpoint with that id. */
    public static class EndpointNotFoundException extends RuntimeException {
        public EndpointNotFoundException(Long id) {
            super("no endpoint with id " + id);
        }
    }

    /** 409 — the seven-dimension identity is already taken. */
    public static class DuplicateEndpointException extends RuntimeException {
        public DuplicateEndpointException(String tuple, Long existingId) {
            super("endpoint " + tuple + " already exists as #" + existingId);
        }
    }
}
