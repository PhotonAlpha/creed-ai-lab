package com.creed.resource.envmatrix.api;

import com.creed.resource.envmatrix.api.dto.LinkDirection;
import com.creed.resource.envmatrix.api.dto.ReleaseStatus;
import com.creed.resource.envmatrix.domain.EnvRelease;
import com.creed.resource.envmatrix.domain.EnvReleaseLink;
import com.creed.resource.envmatrix.domain.EnvReleaseLinkRepository;
import com.creed.resource.envmatrix.domain.EnvReleaseNode;
import com.creed.resource.envmatrix.domain.EnvReleaseNodeRepository;
import com.creed.resource.envmatrix.domain.EnvReleaseRepository;
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

import java.util.LinkedHashMap;
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
class ReleaseControllerTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    EnvReleaseRepository releaseRepository;
    @Autowired
    EnvReleaseNodeRepository nodeRepository;
    @Autowired
    EnvReleaseLinkRepository linkRepository;
    @Autowired
    ObjectMapper objectMapper;

    Long releaseId;
    Long otherReleaseId;
    Long sgCcs;
    Long globalCcs;

    /**
     * Fixture: the reported chain, minus its last hop — {@code SG CCS SIT3 -> Global-CCS SIT2}.
     * A second release exists so every "scoped to one release" assertion has something it could
     * wrongly touch.
     */
    @BeforeEach
    void seed() {
        linkRepository.deleteAll();
        nodeRepository.deleteAll();
        releaseRepository.deleteAll();

        releaseId = releaseRepository.save(release("R2025.09-SIT", "SIT", ReleaseStatus.ACTIVE)).getId();
        otherReleaseId = releaseRepository.save(release("BASELINE-UAT", "UAT", ReleaseStatus.DRAFT)).getId();

        sgCcs = nodeRepository.save(node(releaseId, "CCS", "SG", "SIT3")).getId();
        globalCcs = nodeRepository.save(node(releaseId, "Global-CCS", "*", "SIT2")).getId();
        nodeRepository.save(node(otherReleaseId, "CCS", "*", "UAT1"));

        linkRepository.save(link(releaseId, sgCcs, globalCcs, LinkDirection.ONE_WAY));
    }

    private static EnvRelease release(String name, String tier, ReleaseStatus status) {
        EnvRelease r = new EnvRelease();
        r.setName(name);
        r.setTier(tier);
        r.setStatus(status);
        return r;
    }

    private static EnvReleaseNode node(Long releaseId, String app, String country, String env) {
        EnvReleaseNode n = new EnvReleaseNode();
        n.setReleaseId(releaseId);
        n.setAppSystem(app);
        n.setCountry(country);
        n.setEnvInstance(env);
        return n;
    }

    private static EnvReleaseLink link(Long releaseId, Long source, Long target, LinkDirection direction) {
        EnvReleaseLink l = new EnvReleaseLink();
        l.setReleaseId(releaseId);
        l.setSourceNodeId(source);
        l.setTargetNodeId(target);
        l.setDirection(direction);
        return l;
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    /** Jackson's Map.of() rejects nulls and scrambles order; these payloads need both. */
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ---------------------------------------------------------------- releases

    @Test
    @DisplayName("GET /releases lists all, and filters by tier and status")
    void listsReleases() throws Exception {
        mockMvc.perform(get("/api/env-matrix/releases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        mockMvc.perform(get("/api/env-matrix/releases").param("tier", "SIT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("R2025.09-SIT"))
                // The picker shows these so an empty release is obvious before you select it.
                .andExpect(jsonPath("$[0].nodeCount").value(2))
                .andExpect(jsonPath("$[0].linkCount").value(1));

        mockMvc.perform(get("/api/env-matrix/releases").param("status", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("BASELINE-UAT"));
    }

    @Test
    @DisplayName("POST /releases creates one; a duplicate name is 409")
    void createsRelease() throws Exception {
        mockMvc.perform(post("/api/env-matrix/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("name", "R2025.10-SIT", "tier", "SIT", "status", "DRAFT"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nodeCount").value(0));

        mockMvc.perform(post("/api/env-matrix/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("name", "R2025.09-SIT", "tier", "SIT", "status", "DRAFT"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("duplicate_release"));
    }

    @Test
    @DisplayName("DELETE /releases/{id} takes its participants and links with it")
    void deleteCascades() throws Exception {
        mockMvc.perform(delete("/api/env-matrix/releases/" + releaseId)).andExpect(status().isNoContent());

        assertThat(nodeRepository.findByReleaseIdOrderByAppSystemAscCountryAscEnvInstanceAsc(releaseId)).isEmpty();
        assertThat(linkRepository.findByReleaseIdOrderByIdAsc(releaseId)).isEmpty();
        // The other release is untouched.
        assertThat(nodeRepository.findByReleaseIdOrderByAppSystemAscCountryAscEnvInstanceAsc(otherReleaseId))
                .hasSize(1);

        mockMvc.perform(delete("/api/env-matrix/releases/" + releaseId)).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- topology

    @Test
    @DisplayName("GET /releases/{id}/topology returns the release, its participants and its links")
    void readsTopology() throws Exception {
        mockMvc.perform(get("/api/env-matrix/releases/" + releaseId + "/topology"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.release.name").value("R2025.09-SIT"))
                .andExpect(jsonPath("$.nodes", hasSize(2)))
                .andExpect(jsonPath("$.links", hasSize(1)))
                .andExpect(jsonPath("$.links[0].sourceNodeId").value(sgCcs));
    }

    @Test
    @DisplayName("PUT topology: a new participant and a link to it save in one request via `ref`")
    void savesNewNodeAndLinkTogether() throws Exception {
        mockMvc.perform(put("/api/env-matrix/releases/" + releaseId + "/topology")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map(
                                "nodes", List.of(
                                        map("id", sgCcs, "appSystem", "CCS", "country", "SG", "envInstance", "SIT3"),
                                        map("id", globalCcs, "appSystem", "Global-CCS", "country", "*", "envInstance", "SIT2"),
                                        // The third hop of the reported chain, created here.
                                        map("ref", "n1", "appSystem", "CCS", "country", "CN", "envInstance", "SIT5")),
                                "links", List.of(
                                        map("id", linkRepository.findByReleaseIdOrderByIdAsc(releaseId).get(0).getId(),
                                                "source", map("id", sgCcs), "target", map("id", globalCcs),
                                                "direction", "ONE_WAY"),
                                        map("source", map("id", globalCcs), "target", map("ref", "n1"),
                                                "direction", "ONE_WAY"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.nodesInserted").value(1))
                .andExpect(jsonPath("$.linksInserted").value(1))
                .andExpect(jsonPath("$.nodesUpdated").value(0));

        List<EnvReleaseLink> links = linkRepository.findByReleaseIdOrderByIdAsc(releaseId);
        assertThat(links).hasSize(2);
        // The ref resolved to the id the new participant actually got.
        Long cnCcs = nodeRepository.findByReleaseIdOrderByAppSystemAscCountryAscEnvInstanceAsc(releaseId).stream()
                .filter(n -> "CN".equals(n.getCountry())).findFirst().orElseThrow().getId();
        assertThat(links.get(1).getTargetNodeId()).isEqualTo(cnCcs);
    }

    @Test
    @DisplayName("PUT topology stores layer and sortOrder, and reads them back")
    void savesLayout() throws Exception {
        mockMvc.perform(put("/api/env-matrix/releases/" + releaseId + "/topology")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map(
                                "nodes", List.of(
                                        map("id", sgCcs, "appSystem", "CCS", "country", "SG",
                                                "envInstance", "SIT3", "layer", 3, "sortOrder", -2),
                                        // No layer at all: the graph derives this one from the links.
                                        map("id", globalCcs, "appSystem", "Global-CCS", "country", "*",
                                                "envInstance", "SIT2")),
                                "links", List.of(
                                        map("id", linkRepository.findByReleaseIdOrderByIdAsc(releaseId).get(0).getId(),
                                                "source", map("id", sgCcs), "target", map("id", globalCcs),
                                                "direction", "ONE_WAY"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodesUpdated").value(1));

        mockMvc.perform(get("/api/env-matrix/releases/" + releaseId + "/topology"))
                .andExpect(status().isOk())
                // Ordered by app system, so CCS/SG is first and Global-CCS second.
                .andExpect(jsonPath("$.nodes[0].layer").value(3))
                .andExpect(jsonPath("$.nodes[0].sortOrder").value(-2))
                // Never pinned, so the reader gets an explicit null rather than a made-up 0.
                .andExpect(jsonPath("$.nodes[1].layer").doesNotExist())
                .andExpect(jsonPath("$.nodes[1].sortOrder").value(0));
    }

    @Test
    @DisplayName("PUT topology: clearing a pinned layer hands the participant back to the links")
    void clearsLayer() throws Exception {
        EnvReleaseNode pinned = nodeRepository.findById(sgCcs).orElseThrow();
        pinned.setLayer(4);
        nodeRepository.save(pinned);

        mockMvc.perform(put("/api/env-matrix/releases/" + releaseId + "/topology")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map(
                                "nodes", List.of(
                                        map("id", sgCcs, "appSystem", "CCS", "country", "SG",
                                                "envInstance", "SIT3", "layer", null),
                                        map("id", globalCcs, "appSystem", "Global-CCS", "country", "*",
                                                "envInstance", "SIT2")),
                                "links", List.of(
                                        map("id", linkRepository.findByReleaseIdOrderByIdAsc(releaseId).get(0).getId(),
                                                "source", map("id", sgCcs), "target", map("id", globalCcs),
                                                "direction", "ONE_WAY"))))))
                .andExpect(status().isOk());

        assertThat(nodeRepository.findById(sgCcs).orElseThrow().getLayer()).isNull();
    }

    @Test
    @DisplayName("PUT topology rejects a layer outside the drawable range, writing nothing")
    void rejectsOutOfRangeLayer() throws Exception {
        mockMvc.perform(put("/api/env-matrix/releases/" + releaseId + "/topology")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map(
                                "nodes", List.of(
                                        map("id", sgCcs, "appSystem", "CCS", "country", "SG",
                                                "envInstance", "SIT3", "layer", 400)),
                                "links", List.of()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.issues", hasSize(1)))
                .andExpect(jsonPath("$.issues[0].section").value("nodes"))
                .andExpect(jsonPath("$.issues[0].field").value("layer"));

        // The rejection is total: the participant the payload would have deleted is still there.
        assertThat(nodeRepository.findByReleaseIdOrderByAppSystemAscCountryAscEnvInstanceAsc(releaseId)).hasSize(2);
    }

    @Test
    @DisplayName("PUT topology deletes what the payload omits, and only inside this release")
    void saveIsAuthoritativeForOneRelease() throws Exception {
        mockMvc.perform(put("/api/env-matrix/releases/" + releaseId + "/topology")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map(
                                "nodes", List.of(
                                        map("id", sgCcs, "appSystem", "CCS", "country", "SG", "envInstance", "SIT3")),
                                "links", List.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodesDeleted").value(1))
                .andExpect(jsonPath("$.linksDeleted").value(1));

        assertThat(nodeRepository.findByReleaseIdOrderByAppSystemAscCountryAscEnvInstanceAsc(releaseId)).hasSize(1);
        assertThat(nodeRepository.findByReleaseIdOrderByAppSystemAscCountryAscEnvInstanceAsc(otherReleaseId))
                .hasSize(1);
    }

    @Test
    @DisplayName("PUT topology rejects a duplicate participant, writing nothing")
    void rejectsDuplicateParticipant() throws Exception {
        mockMvc.perform(put("/api/env-matrix/releases/" + releaseId + "/topology")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map(
                                "nodes", List.of(
                                        map("ref", "a", "appSystem", "CCS", "country", "SG", "envInstance", "SIT3"),
                                        map("ref", "b", "appSystem", "CCS", "country", "SG", "envInstance", "SIT3")),
                                "links", List.of()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.issues", hasSize(1)))
                .andExpect(jsonPath("$.issues[0].section").value("nodes"))
                .andExpect(jsonPath("$.issues[0].index").value(1));

        assertThat(nodeRepository.findByReleaseIdOrderByAppSystemAscCountryAscEnvInstanceAsc(releaseId)).hasSize(2);
    }

    @Test
    @DisplayName("PUT topology rejects a link whose end is not in the payload")
    void rejectsDanglingLinkEnd() throws Exception {
        mockMvc.perform(put("/api/env-matrix/releases/" + releaseId + "/topology")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map(
                                // globalCcs is dropped from the payload but a link still points at it.
                                "nodes", List.of(
                                        map("id", sgCcs, "appSystem", "CCS", "country", "SG", "envInstance", "SIT3")),
                                "links", List.of(
                                        map("source", map("id", sgCcs), "target", map("id", globalCcs),
                                                "direction", "ONE_WAY"))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.issues[0].section").value("links"))
                .andExpect(jsonPath("$.issues[0].field").value("target"));
    }

    @Test
    @DisplayName("PUT topology rejects an unresolvable ref and a self-link")
    void rejectsBadRefAndSelfLink() throws Exception {
        mockMvc.perform(put("/api/env-matrix/releases/" + releaseId + "/topology")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map(
                                "nodes", List.of(
                                        map("id", sgCcs, "appSystem", "CCS", "country", "SG", "envInstance", "SIT3")),
                                "links", List.of(
                                        map("source", map("id", sgCcs), "target", map("ref", "nope"),
                                                "direction", "ONE_WAY"),
                                        map("source", map("id", sgCcs), "target", map("id", sgCcs),
                                                "direction", "ONE_WAY"))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.issues", hasSize(2)))
                .andExpect(jsonPath("$.issues[0].message").value(
                        org.hamcrest.Matchers.containsString("ref:nope")))
                .andExpect(jsonPath("$.issues[1].message").value(
                        org.hamcrest.Matchers.containsString("same participant")));
    }

    @Test
    @DisplayName("PUT topology rejects A->B alongside B->A — that is what BIDIRECTIONAL is for")
    void rejectsReciprocalPair() throws Exception {
        mockMvc.perform(put("/api/env-matrix/releases/" + releaseId + "/topology")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map(
                                "nodes", List.of(
                                        map("id", sgCcs, "appSystem", "CCS", "country", "SG", "envInstance", "SIT3"),
                                        map("id", globalCcs, "appSystem", "Global-CCS", "country", "*", "envInstance", "SIT2")),
                                "links", List.of(
                                        map("source", map("id", sgCcs), "target", map("id", globalCcs),
                                                "direction", "ONE_WAY"),
                                        map("source", map("id", globalCcs), "target", map("id", sgCcs),
                                                "direction", "ONE_WAY"))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.issues[0].message").value(
                        org.hamcrest.Matchers.containsString("already connected")));
    }

    @Test
    @DisplayName("PUT topology refuses a participant belonging to a different release")
    void rejectsForeignParticipant() throws Exception {
        Long foreign = nodeRepository
                .findByReleaseIdOrderByAppSystemAscCountryAscEnvInstanceAsc(otherReleaseId)
                .get(0).getId();

        mockMvc.perform(put("/api/env-matrix/releases/" + releaseId + "/topology")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map(
                                "nodes", List.of(
                                        map("id", foreign, "appSystem", "CCS", "country", "*", "envInstance", "UAT1")),
                                "links", List.of()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.issues[0].field").value("id"));
    }
}
