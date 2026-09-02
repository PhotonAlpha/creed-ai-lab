package com.creed.resource.envmatrix.api;

import com.creed.resource.envmatrix.domain.EnvEndpoint;
import com.creed.resource.envmatrix.domain.EnvEndpointRepository;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EnvMatrixControllerTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    EnvEndpointRepository repository;
    @Autowired
    ObjectMapper objectMapper;

    /**
     * Fixture: UAT1/CN has an MS1↔MS2 host:port clash; UAT2/SG is clean. Small on purpose, so a
     * failing assertion points at one row rather than at 1200 seeded ones.
     */
    @BeforeEach
    void seed() {
        repository.deleteAll();
        repository.saveAll(List.of(
                row("MS", "UAT", "UAT1", "CN", "MS1", "Green", "https", "ms1.cn.uat1", "10.1.1.11", 8443),
                row("MS", "UAT", "UAT1", "CN", "MS2", "Green", "https", "ms1.cn.uat1", "10.1.1.12", 8443),
                row("MS", "UAT", "UAT1", "SG", "MS1", "Green", "https", "ms1.sg.uat1", "10.1.2.11", 8443),
                row("CCS", "SIT", "SIT1", "CN", "CCS1", "Green", "http", "ccs1.cn.sit1", "10.2.1.11", 8080)));
    }

    private static EnvEndpoint row(String appSystem, String tier, String env, String country,
                                   String service, String instance, String scheme,
                                   String host, String ip, int port) {
        EnvEndpoint e = new EnvEndpoint();
        e.setAppSystem(appSystem);
        e.setTier(tier);
        e.setEnvInstance(env);
        e.setCountry(country);
        e.setService(service);
        e.setInstance(instance);
        e.setScheme(scheme);
        e.setHost(host);
        e.setIp(ip);
        e.setPort(port);
        return e;
    }

    private Map<String, Object> request(String appSystem, String tier, String env, String country,
                                        String service, String instance, String scheme,
                                        String host, String ip, int port) {
        return Map.of("appSystem", appSystem, "tier", tier, "envInstance", env, "country", country,
                "service", service, "instance", instance, "scheme", scheme,
                "host", host, "ip", ip, "port", port);
    }

    // ------------------------------------------------------------------ reads

    @Test
    @DisplayName("ping reports the service and the health-probe mode")
    void pingReportsProbeMode() throws Exception {
        mockMvc.perform(get("/api/env-matrix/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("creed-resource-env-matrix"))
                .andExpect(jsonPath("$.healthProbeMode").value("mock"));
    }

    @Test
    @DisplayName("dimensions are derived from the rows actually present")
    void dimensionsComeFromData() throws Exception {
        mockMvc.perform(get("/api/env-matrix/dimensions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appSystem", containsInAnyOrder("MS", "CCS")))
                .andExpect(jsonPath("$.tier", containsInAnyOrder("UAT", "SIT")))
                .andExpect(jsonPath("$.country", containsInAnyOrder("CN", "SG")))
                .andExpect(jsonPath("$.scheme", containsInAnyOrder("http", "https")));
    }

    /**
     * The filter is bound as a value object from repeated query parameters. If that binding ever
     * silently breaks, every filter would become a no-op and the UI would look subtly wrong rather
     * than fail — hence an explicit test.
     */
    @Test
    @DisplayName("filters bind from repeated query parameters and actually narrow the result")
    void filtersBindFromRepeatedQueryParameters() throws Exception {
        mockMvc.perform(get("/api/env-matrix/endpoints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)));

        // single value
        mockMvc.perform(get("/api/env-matrix/endpoints").param("tier", "SIT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].service").value("CCS1"));

        // repeated -> IN (...)
        mockMvc.perform(get("/api/env-matrix/endpoints").param("tier", "SIT").param("tier", "UAT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)));

        // two dimensions are ANDed
        mockMvc.perform(get("/api/env-matrix/endpoints").param("tier", "UAT").param("country", "SG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].country").value("SG"));

        // scheme filter — the http/https requirement
        mockMvc.perform(get("/api/env-matrix/endpoints").param("scheme", "http"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].scheme").value("http"));

        // free-text keyword across host/ip/service/note
        mockMvc.perform(get("/api/env-matrix/endpoints").param("keyword", "10.1.2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].ip").value("10.1.2.11"));
    }

    @Test
    @DisplayName("endpoints carry a derived url and a mocked health state")
    void endpointsCarryDerivedFields() throws Exception {
        mockMvc.perform(get("/api/env-matrix/endpoints").param("service", "CCS1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].url").value("http://ccs1.cn.sit1:8080"))
                .andExpect(jsonPath("$[0].health").exists());
    }

    @Test
    @DisplayName("matrix returns service rows, country columns and flags the conflicting cell")
    void matrixAggregatesAndFlagsConflicts() throws Exception {
        mockMvc.perform(get("/api/env-matrix/matrix").param("tier", "UAT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.scope").value("TIER_ENV"))
                .andExpect(jsonPath("$.services", containsInAnyOrder("MS1", "MS2")))
                .andExpect(jsonPath("$.countries", containsInAnyOrder("CN", "SG")))
                // MS1/CN and MS2/CN collide, MS1/SG does not
                .andExpect(jsonPath("$.conflicts", hasSize(1)))
                .andExpect(jsonPath("$.conflicts[0].kind").value("HOST_PORT"))
                .andExpect(jsonPath("$.conflicts[0].value").value("ms1.cn.uat1:8443"))
                .andExpect(jsonPath("$.conflicts[0].endpoints", hasSize(2)));
    }

    @Test
    @DisplayName("filtering down to one side of a clash removes the conflict from the view")
    void conflictsAreComputedOnTheFilteredSet() throws Exception {
        mockMvc.perform(get("/api/env-matrix/conflicts").param("tier", "UAT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // Only MS1 remains, so nothing collides within the visible set.
        mockMvc.perform(get("/api/env-matrix/conflicts").param("service", "MS1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("health reports mode=mock and a per-state summary; recheck rotates the seed")
    void healthIsMockedAndRecheckable() throws Exception {
        mockMvc.perform(get("/api/env-matrix/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("mock"))
                .andExpect(jsonPath("$.mocked").value(true))
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.summary").exists());

        mockMvc.perform(post("/api/env-matrix/health/recheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mocked").value(true))
                .andExpect(jsonPath("$.seed", greaterThan(0)));
    }

    // ----------------------------------------------------------------- writes

    @Test
    @DisplayName("create then read back, and the new row appears in the dimensions")
    void createAndFetch() throws Exception {
        String payload = objectMapper.writeValueAsString(request(
                "AliYunTeir", "NFT", "NFT1", "GD", "AliYunTeir1", "Green", "https",
                "aliyun1.gd.nft1", "10.9.5.11", 8500));

        String created = mockMvc.perform(post("/api/env-matrix/endpoints")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.url").value("https://aliyun1.gd.nft1:8500"))
                .andExpect(jsonPath("$.conflict").value(false))
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(created).get("id").asLong();

        mockMvc.perform(get("/api/env-matrix/endpoints/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("AliYunTeir1"));

        mockMvc.perform(get("/api/env-matrix/dimensions"))
                .andExpect(jsonPath("$.country", containsInAnyOrder("CN", "SG", "GD")));
    }

    @Test
    @DisplayName("a second row with the same seven-dimension identity is rejected with 409")
    void duplicateDimensionsConflict() throws Exception {
        String payload = objectMapper.writeValueAsString(request(
                "MS", "UAT", "UAT1", "CN", "MS1", "Green", "https", "other.host", "10.1.1.99", 9443));

        mockMvc.perform(post("/api/env-matrix/endpoints")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("duplicate_endpoint"));
    }

    @Test
    @DisplayName("an invalid scheme or port is rejected with a field-level 400")
    void validationRejectsBadPayload() throws Exception {
        String badScheme = objectMapper.writeValueAsString(request(
                "MS", "UAT", "UAT3", "CN", "MS9", "Green", "ftp", "h", "10.1.1.98", 8443));
        mockMvc.perform(post("/api/env-matrix/endpoints")
                        .contentType(MediaType.APPLICATION_JSON).content(badScheme))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.fields[0].field").value("scheme"));

        String badPort = objectMapper.writeValueAsString(request(
                "MS", "UAT", "UAT3", "CN", "MS9", "Green", "https", "h", "10.1.1.97", 70000));
        mockMvc.perform(post("/api/env-matrix/endpoints")
                        .contentType(MediaType.APPLICATION_JSON).content(badPort))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("port"));
    }

    @Test
    @DisplayName("recording a genuine conflict is allowed and reported, not blocked")
    void conflictingRowIsAcceptedAndFlagged() throws Exception {
        String payload = objectMapper.writeValueAsString(request(
                "MS", "UAT", "UAT1", "CN", "MS3", "Green", "https", "ms1.cn.uat1", "10.1.1.13", 8443));

        mockMvc.perform(post("/api/env-matrix/endpoints")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conflict").value(true))
                .andExpect(jsonPath("$.conflictKeys[0]").value("host:port ms1.cn.uat1:8443"));
    }

    @Test
    @DisplayName("update changes the mapping; delete removes the row and 404s afterwards")
    void updateAndDelete() throws Exception {
        Long id = repository.findAll().stream()
                .filter(e -> "CCS1".equals(e.getService())).findFirst().orElseThrow().getId();

        String payload = objectMapper.writeValueAsString(request(
                "CCS", "SIT", "SIT1", "CN", "CCS1", "Green", "http", "ccs1-moved.cn.sit1", "10.2.1.44", 8081));

        mockMvc.perform(put("/api/env-matrix/endpoints/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value("ccs1-moved.cn.sit1"))
                .andExpect(jsonPath("$.port").value(8081));

        mockMvc.perform(delete("/api/env-matrix/endpoints/{id}", id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/env-matrix/endpoints/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    @DisplayName("batch save inserts, updates and (with deleteMissing) removes in one transaction")
    void batchSaveAppliesTheWholeTable() throws Exception {
        EnvEndpoint keep = repository.findAll().stream()
                .filter(e -> "MS1".equals(e.getService()) && "CN".equals(e.getCountry()))
                .findFirst().orElseThrow();

        Map<String, Object> updatedRow = new java.util.LinkedHashMap<>(request(
                "MS", "UAT", "UAT1", "CN", "MS1", "Green", "https", "ms1-renamed.cn.uat1", "10.1.1.11", 8443));
        updatedRow.put("id", keep.getId());

        Map<String, Object> newRow = request(
                "TencentTeir", "PROD", "PROD1", "ID", "TencentTeir1", "Green", "https",
                "tencent1.id.prod1", "10.8.6.11", 8600);

        String payload = objectMapper.writeValueAsString(Map.of(
                "endpoints", List.of(updatedRow, newRow),
                "deleteMissing", true));

        mockMvc.perform(put("/api/env-matrix/endpoints")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.inserted").value(1))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.deleted").value(3));

        assertThat(repository.findAll()).hasSize(2);
        assertThat(repository.findById(keep.getId()).orElseThrow().getHost()).isEqualTo("ms1-renamed.cn.uat1");
    }

    @Test
    @DisplayName("resubmitting unchanged rows reports 0 updated and leaves versions alone")
    void batchSaveIgnoresUnchangedRows() throws Exception {
        // The config page always submits the whole table, so an edit to one row arrives together
        // with every untouched row. Those must not be counted — or written.
        List<Map<String, Object>> payloadRows = repository.findAll().stream()
                .map(e -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>(request(
                            e.getAppSystem(), e.getTier(), e.getEnvInstance(), e.getCountry(),
                            e.getService(), e.getInstance(), e.getScheme(),
                            e.getHost(), e.getIp(), e.getPort()));
                    row.put("id", e.getId());
                    return row;
                })
                .toList();

        String payload = objectMapper.writeValueAsString(Map.of(
                "endpoints", payloadRows, "deleteMissing", true));

        mockMvc.perform(put("/api/env-matrix/endpoints")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.inserted").value(0))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.deleted").value(0));

        assertThat(repository.findAll()).allSatisfy(e -> assertThat(e.getVersion()).isZero());
    }

    @Test
    @DisplayName("a duplicate identity inside the payload rejects the whole save with 422")
    void batchSaveRejectsDuplicateRowsAndWritesNothing() throws Exception {
        Map<String, Object> row = request(
                "MS", "UAT", "UAT4", "CN", "MS7", "Green", "https", "a.host", "10.1.1.71", 8443);
        Map<String, Object> duplicate = request(
                "MS", "UAT", "UAT4", "CN", "MS7", "Green", "https", "b.host", "10.1.1.72", 8443);

        String payload = objectMapper.writeValueAsString(Map.of(
                "endpoints", List.of(row, duplicate),
                "deleteMissing", false));

        mockMvc.perform(put("/api/env-matrix/endpoints")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.issues", hasSize(1)))
                .andExpect(jsonPath("$.issues[0].index").value(1))
                .andExpect(jsonPath("$.issues[0].field").value("dimensions"));

        // Nothing was written — the fixture is untouched.
        assertThat(repository.findAll()).hasSize(4);
    }
}
