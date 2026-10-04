package com.creed.resource.envmatrix.api;

import com.creed.resource.envmatrix.domain.EnvAesRecordRepository;
import com.creed.resource.envmatrix.domain.EnvEndpoint;
import com.creed.resource.envmatrix.domain.EnvEndpointRepository;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AesControllerTest {

    private static final String BASE = "/api/env-matrix/aes";
    private static final String SECRET = "creed-secret";
    private static final String IV = "0123456789abcdef";
    private static final String RANDOM = "r4nd0m";
    /** "db-p@ss 密码" under the keys above — see AesCryptoServiceTest. */
    private static final String VECTOR = "EFZDc/Ok6Ib89heJb1OkHw==";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    EnvEndpointRepository endpointRepository;
    @Autowired
    EnvAesRecordRepository recordRepository;
    @Autowired
    ObjectMapper objectMapper;

    /** Two MS servers, one of which carries two endpoints (http + https), and one CCS server. */
    @BeforeEach
    void seed() {
        recordRepository.deleteAll();
        endpointRepository.deleteAll();
        endpointRepository.saveAll(List.of(
                endpoint("MS", "MS1", "https", "ms1.cn.uat1", "10.1.1.11", 8443),
                endpoint("MS", "MS1", "http", "ms1.cn.uat1", "10.1.1.11", 8080),
                endpoint("MS", "MS2", "https", "ms2.cn.uat1", "10.1.1.12", 8443),
                endpoint("CCS", "CCS1", "http", "ccs1.cn.sit1", "10.2.1.11", 8080)));
    }

    private static EnvEndpoint endpoint(String appSystem, String service, String scheme, String host, String ip, int port) {
        EnvEndpoint e = new EnvEndpoint();
        e.setAppSystem(appSystem);
        e.setTier("UAT");
        e.setEnvInstance("UAT1");
        e.setCountry("CN");
        e.setService(service);
        e.setInstance("Green");
        e.setScheme(scheme);
        e.setHost(host);
        e.setIp(ip);
        e.setPort(port);
        return e;
    }

    private ResultActions postJson(String path, Object body) throws Exception {
        return mockMvc.perform(post(BASE + path).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private static Map<String, Object> keys(Object... extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("secretKey", SECRET);
        body.put("iv", IV);
        body.put("randomKey", RANDOM);
        for (int i = 0; i < extra.length; i += 2) {
            body.put((String) extra[i], extra[i + 1]);
        }
        return body;
    }

    private static Map<String, String> server(String appSystem, String host, String ip) {
        return Map.of("appSystem", appSystem, "host", host, "ip", ip);
    }

    private JsonNode saveMsBoth() throws Exception {
        String json = postJson("/records", Map.of(
                "propertyKey", "spring.datasource.password",
                "encryptedValue", VECTOR,
                "randomKey", RANDOM,
                "servers", List.of(server("MS", "ms1.cn.uat1", "10.1.1.11"), server("MS", "ms2.cn.uat1", "10.1.1.12"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    // ------------------------------------------------------------------ servers

    @Test
    @DisplayName("servers: one row per (appSystem, host, ip), filtered by app system")
    void servers() throws Exception {
        mockMvc.perform(get(BASE + "/servers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].appSystem").value("CCS"));
        mockMvc.perform(get(BASE + "/servers").param("appSystem", "MS"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].host").value("ms1.cn.uat1"))
                .andExpect(jsonPath("$[0].ip").value("10.1.1.11"))
                .andExpect(jsonPath("$[1].host").value("ms2.cn.uat1"));
    }

    // ------------------------------------------------------------------ encrypt / decrypt

    @Test
    @DisplayName("encrypt and decrypt round-trip through the API, decrypt is no-store")
    void encryptDecrypt() throws Exception {
        postJson("/encrypt", keys("plainValue", "db-p@ss 密码"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.encryptedValue").value(VECTOR))
                .andExpect(jsonPath("$.algorithm").value(org.hamcrest.Matchers.containsString("CBC")));
        postJson("/decrypt", keys("encryptedValue", VECTOR))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.plainValue").value("db-p@ss 密码"));
    }

    @Test
    @DisplayName("wrong keys: 422 decrypt_failed, and the body echoes no secret")
    void wrongKeys() throws Exception {
        Map<String, Object> body = keys("encryptedValue", VECTOR);
        body.put("randomKey", "other");
        String response = postJson("/decrypt", body)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("decrypt_failed"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(SECRET, IV, "other", VECTOR);
    }

    @Test
    @DisplayName("an IV that is not 16 bytes is a 400 naming the iv field")
    void badIv() throws Exception {
        Map<String, Object> body = keys("plainValue", "x");
        body.put("iv", "short");
        postJson("/encrypt", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.fields[0].field").value("iv"));
    }

    @Test
    @DisplayName("bean validation: a missing Secret Key is a 400")
    void missingSecret() throws Exception {
        Map<String, Object> body = keys("plainValue", "x");
        body.remove("secretKey");
        postJson("/encrypt", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("secretKey"));
    }

    // ------------------------------------------------------------------ records

    @Test
    @DisplayName("save: one row per server; saving again replaces the value, unchanged rows are not counted")
    void saveUpserts() throws Exception {
        JsonNode first = saveMsBoth();
        assertThat(first.get("inserted").asInt()).isEqualTo(2);
        assertThat(first.get("records")).hasSize(2);

        // Same value again: nothing changes.
        JsonNode again = saveMsBoth();
        assertThat(again.get("inserted").asInt()).isZero();
        assertThat(again.get("updated").asInt()).isZero();

        // A new value for one of them: one update, no insert.
        postJson("/records", Map.of(
                "propertyKey", "spring.datasource.password",
                "encryptedValue", "16yJ7k5vZlC4Dylj/nGKeA==",
                "servers", List.of(server("MS", "ms1.cn.uat1", "10.1.1.11"))))
                .andExpect(jsonPath("$.inserted").value(0))
                .andExpect(jsonPath("$.updated").value(1));
        assertThat(recordRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("the randomkey is stored and listed per row; a new randomkey alone is an update")
    void randomKeyStored() throws Exception {
        saveMsBoth();
        mockMvc.perform(get(BASE + "/records").param("appSystem", "MS"))
                .andExpect(jsonPath("$[0].randomKey").value(RANDOM))
                .andExpect(jsonPath("$[1].randomKey").value(RANDOM));

        postJson("/records", Map.of(
                "propertyKey", "spring.datasource.password", "encryptedValue", VECTOR, "randomKey", "  spaced ",
                "servers", List.of(server("MS", "ms1.cn.uat1", "10.1.1.11"))))
                .andExpect(jsonPath("$.updated").value(1))
                // Not trimmed: spaces in a randomkey are part of the hashed material.
                .andExpect(jsonPath("$.records[0].randomKey").value("  spaced "));

        // An empty randomkey is "none", stored as null.
        postJson("/records", Map.of(
                "propertyKey", "token", "encryptedValue", VECTOR, "randomKey", "",
                "servers", List.of(server("CCS", "ccs1.cn.sit1", "10.2.1.11"))))
                .andExpect(jsonPath("$.records[0].randomKey").doesNotExist());
    }

    @Test
    @DisplayName("list filters by app system and property key")
    void list() throws Exception {
        saveMsBoth();
        postJson("/records", Map.of("propertyKey", "token", "encryptedValue", VECTOR,
                "servers", List.of(server("CCS", "ccs1.cn.sit1", "10.2.1.11"))))
                .andExpect(status().isOk());

        mockMvc.perform(get(BASE + "/records")).andExpect(jsonPath("$", hasSize(3)));
        mockMvc.perform(get(BASE + "/records").param("appSystem", "MS")).andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get(BASE + "/records").param("propertyKey", "token"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].appSystem").value("CCS"));
    }

    @Test
    @DisplayName("update: 409 when it would collide with another row; 404 for an unknown id")
    void update() throws Exception {
        JsonNode saved = saveMsBoth();
        long first = saved.get("records").get(0).get("id").asLong();
        long second = saved.get("records").get(1).get("id").asLong();

        Map<String, Object> clash = new LinkedHashMap<>(Map.of(
                "appSystem", "MS", "host", "ms2.cn.uat1", "ip", "10.1.1.12",
                "propertyKey", "spring.datasource.password", "encryptedValue", VECTOR));
        mockMvc.perform(put(BASE + "/records/" + first).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(clash)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("duplicate_aes_record"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("#" + second)));

        clash.put("propertyKey", "spring.datasource.username");
        clash.put("randomKey", "rotated");
        clash.put("note", "renamed");
        mockMvc.perform(put(BASE + "/records/" + first).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(clash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propertyKey").value("spring.datasource.username"))
                .andExpect(jsonPath("$.randomKey").value("rotated"))
                .andExpect(jsonPath("$.note").value("renamed"));

        mockMvc.perform(put(BASE + "/records/999999").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(clash)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("delete: several ids at once; unknown ids are ignored")
    void deleteMany() throws Exception {
        JsonNode saved = saveMsBoth();
        mockMvc.perform(delete(BASE + "/records")
                        .param("ids", saved.get("records").get(0).get("id").asText(), "999999"))
                .andExpect(status().isNoContent());
        assertThat(recordRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("decrypt records: per-row results, in request order, a bad row does not hide good ones")
    void decryptRecords() throws Exception {
        JsonNode saved = saveMsBoth();
        long good = saved.get("records").get(0).get("id").asLong();
        // A row encrypted with no randomkey: valid ciphertext, wrong keys for this request.
        long other = objectMapper.readTree(postJson("/records", Map.of(
                        "propertyKey", "other", "encryptedValue", "16yJ7k5vZlC4Dylj/nGKeA==",
                        "servers", List.of(server("CCS", "ccs1.cn.sit1", "10.2.1.11"))))
                .andReturn().getResponse().getContentAsString()).get("records").get(0).get("id").asLong();

        postJson("/records/decrypt", Map.of("items", List.of(
                        keys("id", other), keys("id", good), keys("id", 999999))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].id").value(other))
                .andExpect(jsonPath("$[0].error").value("decrypt_failed"))
                .andExpect(jsonPath("$[1].plainValue").value("db-p@ss 密码"))
                .andExpect(jsonPath("$[2].error").value("not_found"));
    }

    @Test
    @DisplayName("decrypt records: each record with its own keys; unusable keys fail only their row")
    void decryptRecordsPerRowKeys() throws Exception {
        long id = saveMsBoth().get("records").get(0).get("id").asLong();
        long other = saveMsBoth().get("records").get(1).get("id").asLong();
        Map<String, Object> badIv = keys("id", other);
        badIv.put("iv", "short");
        postJson("/records/decrypt", Map.of("items", List.of(keys("id", id), badIv)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].plainValue").value("db-p@ss 密码"))
                .andExpect(jsonPath("$[1].error").value("invalid_key_material"))
                .andExpect(jsonPath("$[1].message").value(org.hamcrest.Matchers.startsWith("iv:")));
    }

    // ------------------------------------------------------------------ batches

    @Test
    @DisplayName("encrypt/decrypt batch: every row with its own keys, failures per row, request order kept")
    void cryptoBatch() throws Exception {
        Map<String, Object> plain = keys("value", "db-p@ss 密码");
        Map<String, Object> noRandom = keys("value", "db-p@ss 密码");
        noRandom.put("randomKey", "");
        Map<String, Object> badIv = keys("value", "x");
        badIv.put("iv", "short");
        postJson("/encrypt/batch", Map.of("items", List.of(plain, noRandom, badIv)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].index").value(0))
                .andExpect(jsonPath("$[0].value").value(VECTOR))
                .andExpect(jsonPath("$[1].value").value("16yJ7k5vZlC4Dylj/nGKeA=="))
                .andExpect(jsonPath("$[2].error").value("invalid_key_material"))
                .andExpect(jsonPath("$[2].field").value("iv"));

        Map<String, Object> wrong = keys("value", VECTOR);
        wrong.put("randomKey", "other");
        String body = postJson("/decrypt/batch", Map.of("items", List.of(wrong, keys("value", VECTOR))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].error").value("decrypt_failed"))
                .andExpect(jsonPath("$[1].value").value("db-p@ss 密码"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(SECRET, IV, "other");
    }

    @Test
    @DisplayName("save batch: every item to every server in one call; a repeated property key is a 400")
    void saveBatch() throws Exception {
        List<Map<String, String>> servers = List.of(server("MS", "ms1.cn.uat1", "10.1.1.11"), server("MS", "ms2.cn.uat1", "10.1.1.12"));
        postJson("/records/batch", Map.of(
                "items", List.of(
                        Map.of("propertyKey", "db.password", "encryptedValue", VECTOR, "randomKey", RANDOM),
                        Map.of("propertyKey", "mq.password", "encryptedValue", "16yJ7k5vZlC4Dylj/nGKeA==")),
                "servers", servers))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(4))
                .andExpect(jsonPath("$.records", hasSize(4)));
        mockMvc.perform(get(BASE + "/records").param("propertyKey", "mq.password"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].randomKey").doesNotExist());

        postJson("/records/batch", Map.of(
                "items", List.of(
                        Map.of("propertyKey", "dup", "encryptedValue", VECTOR),
                        Map.of("propertyKey", " dup ", "encryptedValue", VECTOR)),
                "servers", servers))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("items[1].propertyKey"));
        assertThat(recordRepository.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("save validation: no servers is a 400")
    void saveValidation() throws Exception {
        postJson("/records", Map.of("propertyKey", "k", "encryptedValue", VECTOR, "servers", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("servers"));
    }
}
