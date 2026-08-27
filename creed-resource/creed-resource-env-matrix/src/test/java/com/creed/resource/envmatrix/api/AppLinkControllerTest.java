package com.creed.resource.envmatrix.api;

import com.creed.resource.envmatrix.api.dto.LinkDirection;
import com.creed.resource.envmatrix.domain.EnvAppLink;
import com.creed.resource.envmatrix.domain.EnvAppLinkRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
class AppLinkControllerTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    EnvAppLinkRepository repository;
    @Autowired
    ObjectMapper objectMapper;

    /** Two tiers, so every "scoped to one tier" assertion has something it could wrongly touch. */
    @BeforeEach
    void seed() {
        repository.deleteAll();
        repository.saveAll(List.of(
                link("SIT", "Proxy", "MS", LinkDirection.ONE_WAY),
                link("SIT", "CCS", "MS", LinkDirection.ONE_WAY),
                link("SIT", "Global-CCS", "CCS", LinkDirection.BIDIRECTIONAL),
                link("UAT", "Proxy", "MS", LinkDirection.ONE_WAY)));
    }

    private static EnvAppLink link(String tier, String source, String target, LinkDirection direction) {
        EnvAppLink l = new EnvAppLink();
        l.setTier(tier);
        l.setSourceApp(source);
        l.setTargetApp(target);
        l.setDirection(direction);
        return l;
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    @Test
    @DisplayName("GET /links?tier= returns only that tier's wiring")
    void listsByTier() throws Exception {
        mockMvc.perform(get("/api/env-matrix/links").param("tier", "SIT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));

        mockMvc.perform(get("/api/env-matrix/links"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)));
    }

    @Test
    @DisplayName("POST /links creates a link and echoes its direction")
    void creates() throws Exception {
        mockMvc.perform(post("/api/env-matrix/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tier", "SIT", "sourceApp", "CCS", "targetApp", "CCS-FDR",
                                "direction", "BIDIRECTIONAL"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.direction").value("BIDIRECTIONAL"))
                .andExpect(jsonPath("$.id").isNumber());

        assertThat(repository.findByTierAndSourceAppAndTargetApp("SIT", "CCS", "CCS-FDR")).isPresent();
    }

    @Test
    @DisplayName("POST /links rejects a duplicate identity with 409")
    void rejectsDuplicate() throws Exception {
        mockMvc.perform(post("/api/env-matrix/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tier", "SIT", "sourceApp", "Proxy", "targetApp", "MS",
                                "direction", "ONE_WAY"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("duplicate_link"));
    }

    @Test
    @DisplayName("POST /links rejects a self-link with 400")
    void rejectsSelfLink() throws Exception {
        mockMvc.perform(post("/api/env-matrix/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tier", "SIT", "sourceApp", "MS", "targetApp", "MS",
                                "direction", "ONE_WAY"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_link"));
    }

    @Test
    @DisplayName("PUT /links/{id} updates in place")
    void updates() throws Exception {
        Long id = repository.findByTierAndSourceAppAndTargetApp("SIT", "CCS", "MS").orElseThrow().getId();

        mockMvc.perform(put("/api/env-matrix/links/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tier", "SIT", "sourceApp", "CCS", "targetApp", "MS",
                                "direction", "BIDIRECTIONAL", "note", "now two-way"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.direction").value("BIDIRECTIONAL"))
                .andExpect(jsonPath("$.note").value("now two-way"));
    }

    @Test
    @DisplayName("DELETE /links/{id} removes the row; a second delete is 404")
    void deletes() throws Exception {
        Long id = repository.findByTierAndSourceAppAndTargetApp("SIT", "CCS", "MS").orElseThrow().getId();

        mockMvc.perform(delete("/api/env-matrix/links/" + id)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/env-matrix/links/" + id)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /links replaces one tier and leaves the others alone")
    void batchSaveIsScopedToOneTier() throws Exception {
        Long keep = repository.findByTierAndSourceAppAndTargetApp("SIT", "Proxy", "MS").orElseThrow().getId();

        mockMvc.perform(put("/api/env-matrix/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tier", "SIT", "links", List.of(
                                Map.of("id", keep, "tier", "SIT", "sourceApp", "Proxy", "targetApp", "MS",
                                        "direction", "ONE_WAY"),
                                Map.of("tier", "SIT", "sourceApp", "Proxy", "targetApp", "GEB",
                                        "direction", "ONE_WAY"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.inserted").value(1))
                .andExpect(jsonPath("$.updated").value(0))
                // CCS->MS and Global-CCS->CCS were absent from the payload, so they go.
                .andExpect(jsonPath("$.deleted").value(2));

        assertThat(repository.findByTierOrderBySourceAppAscTargetAppAsc("SIT")).hasSize(2);
        assertThat(repository.findByTierOrderBySourceAppAscTargetAppAsc("UAT")).hasSize(1);
    }

    @Test
    @DisplayName("PUT /links rejects duplicates and self-links with 422, writing nothing")
    void batchSaveRejectsBadRows() throws Exception {
        mockMvc.perform(put("/api/env-matrix/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tier", "SIT", "links", List.of(
                                Map.of("tier", "SIT", "sourceApp", "Proxy", "targetApp", "GEB",
                                        "direction", "ONE_WAY"),
                                Map.of("tier", "SIT", "sourceApp", "Proxy", "targetApp", "GEB",
                                        "direction", "ONE_WAY"),
                                Map.of("tier", "SIT", "sourceApp", "MS", "targetApp", "MS",
                                        "direction", "ONE_WAY"))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.issues", hasSize(2)))
                .andExpect(jsonPath("$.issues[0].index").value(1))
                .andExpect(jsonPath("$.issues[1].index").value(2));

        // Nothing was written: the SIT fixture is untouched.
        assertThat(repository.findByTierOrderBySourceAppAscTargetAppAsc("SIT")).hasSize(3);
    }

    @Test
    @DisplayName("PUT /links rejects a row belonging to a different tier than the save targets")
    void batchSaveRejectsForeignTierRows() throws Exception {
        mockMvc.perform(put("/api/env-matrix/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tier", "SIT", "links", List.of(
                                Map.of("tier", "UAT", "sourceApp", "Proxy", "targetApp", "GEB",
                                        "direction", "ONE_WAY"))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.issues[0].field").value("tier"));
    }
}
