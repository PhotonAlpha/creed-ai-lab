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
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
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
    private static final String IV = "0123456789abcdef";
    private static final String RANDOM = "r4nd0m";
    private static final String SALT = "s4lt 盐";
    private static final String PLAIN = "db-p@ss 密码";
    /** PLAIN on ms1.cn.uat1 / 10.1.1.11 — see AesCryptoServiceTest. */
    private static final String VECTOR_MS1 = "H24JNkxfMPSBHI3vtO41cQ==";
    /** PLAIN on ms2.cn.uat1 / 10.1.1.12. */
    private static final String VECTOR_MS2 = "+xwHQYYKsIdcswqiV0wq2Q==";

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

    /** A JSON object from alternating keys and values. */
    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            body.put((String) kv[i], kv[i + 1]);
        }
        return body;
    }

    private static Map<String, String> server(String appSystem, String host, String ip) {
        return Map.of("appSystem", appSystem, "host", host, "ip", ip);
    }

    private static final List<Map<String, String>> MS_BOTH =
            List.of(server("MS", "ms1.cn.uat1", "10.1.1.11"), server("MS", "ms2.cn.uat1", "10.1.1.12"));

    private JsonNode saveMsBoth() throws Exception {
        String json = postJson("/records", obj(
                "propertyKey", "spring.datasource.password", "plainValue", PLAIN, "iv", IV, "salt", SALT, "randomKey", RANDOM,
                "servers", MS_BOTH))
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
    @DisplayName("encrypt/decrypt for one server: Secret Key = randomkey + host + ip, PBKDF2 with the salt; decrypt is no-store")
    void encryptDecrypt() throws Exception {
        postJson("/encrypt", obj("iv", IV, "salt", SALT, "randomKey", RANDOM, "host", "ms1.cn.uat1", "ip", "10.1.1.11", "plainValue", PLAIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.encryptedValue").value(VECTOR_MS1))
                .andExpect(jsonPath("$.algorithm").value(containsString("randomKey + host + ip")))
                .andExpect(jsonPath("$.algorithm").value(containsString("PBKDF2WithHmacSHA256")));
        postJson("/decrypt", obj("iv", IV, "salt", SALT, "randomKey", RANDOM, "host", "ms1.cn.uat1", "ip", "10.1.1.11", "encryptedValue", VECTOR_MS1))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.plainValue").value(PLAIN));
    }

    @Test
    @DisplayName("another server's ciphertext: 422 decrypt_failed, and the body echoes no secret")
    void wrongServer() throws Exception {
        String response = postJson("/decrypt", obj(
                "iv", IV, "salt", SALT, "randomKey", RANDOM, "host", "ms1.cn.uat1", "ip", "10.1.1.11", "encryptedValue", VECTOR_MS2))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("decrypt_failed"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(IV, RANDOM, SALT, VECTOR_MS2);
    }

    @Test
    @DisplayName("an IV that is not 16 bytes is a 400 naming the iv field; host is required")
    void validation() throws Exception {
        postJson("/encrypt", obj("iv", "short", "salt", SALT, "randomKey", RANDOM, "host", "h", "ip", "1.1.1.1", "plainValue", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.fields[0].field").value("iv"));
        postJson("/encrypt", obj("iv", IV, "salt", SALT, "randomKey", RANDOM, "ip", "1.1.1.1", "plainValue", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("host"));
    }

    @Test
    @DisplayName("the salt is required: missing or empty is a 400 naming salt; another salt does not decrypt")
    void saltRequired() throws Exception {
        postJson("/encrypt", obj("iv", IV, "randomKey", RANDOM, "host", "h", "ip", "1.1.1.1", "plainValue", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("salt"));
        postJson("/decrypt", obj("iv", IV, "salt", "", "randomKey", RANDOM, "host", "ms1.cn.uat1", "ip", "10.1.1.11",
                        "encryptedValue", VECTOR_MS1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("salt"));
        postJson("/decrypt", obj("iv", IV, "salt", "other", "randomKey", RANDOM, "host", "ms1.cn.uat1", "ip", "10.1.1.11",
                        "encryptedValue", VECTOR_MS1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("decrypt_failed"));
    }

    // ------------------------------------------------------------------ records

    @Test
    @DisplayName("save: encrypted per server, Secret Key listed per row, nothing re-written when unchanged")
    void saveEncryptsPerServer() throws Exception {
        JsonNode first = saveMsBoth();
        assertThat(first.get("inserted").asInt()).isEqualTo(2);
        JsonNode records = first.get("records");
        assertThat(records.get(0).get("encryptedValue").asText()).isEqualTo(VECTOR_MS1);
        assertThat(records.get(1).get("encryptedValue").asText()).isEqualTo(VECTOR_MS2);
        assertThat(records.get(0).get("secretKey").asText()).isEqualTo("r4nd0mms1.cn.uat110.1.1.11");
        assertThat(records.get(1).get("secretKey").asText()).isEqualTo("r4nd0mms2.cn.uat110.1.1.12");
        assertThat(records.get(0).get("randomKey").asText()).isEqualTo(RANDOM);

        // CBC with a fixed IV is deterministic: the same value re-encrypts to the same bytes.
        JsonNode again = saveMsBoth();
        assertThat(again.get("inserted").asInt()).isZero();
        assertThat(again.get("updated").asInt()).isZero();

        // A new randomkey changes the Secret Key, so the ciphertext changes.
        postJson("/records", obj("propertyKey", "spring.datasource.password", "plainValue", PLAIN, "iv", IV, "salt", SALT,
                "randomKey", "", "servers", List.of(server("MS", "ms1.cn.uat1", "10.1.1.11"))))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.records[0].encryptedValue").value("3kIVwWqttsGhLWV8i/F1pA=="))
                .andExpect(jsonPath("$.records[0].randomKey").doesNotExist())
                .andExpect(jsonPath("$.records[0].secretKey").value("ms1.cn.uat110.1.1.11"));
        assertThat(recordRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("list filters by app system and property key, and carries the derived Secret Key")
    void list() throws Exception {
        saveMsBoth();
        postJson("/records", obj("propertyKey", "token", "plainValue", "t", "iv", IV, "salt", SALT, "randomKey", "x",
                "servers", List.of(server("CCS", "ccs1.cn.sit1", "10.2.1.11"))))
                .andExpect(status().isOk());

        mockMvc.perform(get(BASE + "/records")).andExpect(jsonPath("$", hasSize(3)));
        mockMvc.perform(get(BASE + "/records").param("appSystem", "MS")).andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get(BASE + "/records").param("propertyKey", "token"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].appSystem").value("CCS"))
                .andExpect(jsonPath("$[0].secretKey").value("xccs1.cn.sit110.2.1.11"));
    }

    /**
     * Four records: the datasource password on both MS servers, mq.password on ms1, a token on ccs1.
     * ccs1 gets a second endpoint in SIT1, so it is in two env instances.
     */
    private void seedForPaging() throws Exception {
        saveMsBoth();
        postJson("/records", obj("propertyKey", "mq.password", "plainValue", "m", "iv", IV, "salt", SALT, "randomKey", RANDOM,
                "servers", List.of(server("MS", "ms1.cn.uat1", "10.1.1.11")))).andExpect(status().isOk());
        postJson("/records", obj("propertyKey", "token", "plainValue", "t", "iv", IV, "salt", SALT, "randomKey", "x",
                "servers", List.of(server("CCS", "ccs1.cn.sit1", "10.2.1.11")))).andExpect(status().isOk());
        EnvEndpoint sit = endpoint("CCS", "CCS2", "http", "ccs1.cn.sit1", "10.2.1.11", 9090);
        sit.setTier("SIT");
        sit.setEnvInstance("SIT1");
        endpointRepository.save(sit);
    }

    @Test
    @DisplayName("page: identity order, 1-based pages with a total, filters by env (via endpoints) / host / key, sorts")
    void page() throws Exception {
        seedForPaging();
        mockMvc.perform(get(BASE + "/records/page").param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[*].propertyKey", contains("token", "mq.password", "spring.datasource.password")))
                .andExpect(jsonPath("$.items[0].secretKey").value("xccs1.cn.sit110.2.1.11"));
        mockMvc.perform(get(BASE + "/records/page").param("size", "3").param("page", "2"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].host").value("ms2.cn.uat1"));
        // ccs1 is in UAT1 and SIT1 — one record, matched by either
        mockMvc.perform(get(BASE + "/records/page").param("envInstance", "SIT1"))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].appSystem").value("CCS"));
        mockMvc.perform(get(BASE + "/records/page").param("envInstance", "UAT1")).andExpect(jsonPath("$.total").value(4));
        mockMvc.perform(get(BASE + "/records/page").param("host", "ms1.cn.uat1").param("host", "ccs1.cn.sit1"))
                .andExpect(jsonPath("$.total").value(3));
        mockMvc.perform(get(BASE + "/records/page").param("appSystem", "MS").param("propertyKey", "spring.datasource.password"))
                .andExpect(jsonPath("$.total").value(2));
        mockMvc.perform(get(BASE + "/records/page").param("sort", "host").param("order", "desc"))
                .andExpect(jsonPath("$.items[*].host", contains("ms2.cn.uat1", "ms1.cn.uat1", "ms1.cn.uat1", "ccs1.cn.sit1")));
        mockMvc.perform(get(BASE + "/records/page").param("page", "9")).andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.total").value(4));
    }

    @Test
    @DisplayName("page: size above 100, page 0, an unknown sort or order are 400s naming the parameter")
    void pageValidation() throws Exception {
        mockMvc.perform(get(BASE + "/records/page").param("size", "101"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields[0].field").value("size"));
        mockMvc.perform(get(BASE + "/records/page").param("page", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields[0].field").value("page"));
        mockMvc.perform(get(BASE + "/records/page").param("sort", "encryptedValue"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields[0].field").value("sort"));
        mockMvc.perform(get(BASE + "/records/page").param("order", "sideways"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields[0].field").value("order"));
    }

    @Test
    @DisplayName("filters: each option list is narrowed by the choices to its left, and only offers values records carry")
    void filters() throws Exception {
        seedForPaging();
        mockMvc.perform(get(BASE + "/records/filters"))
                .andExpect(jsonPath("$.envInstances", contains("SIT1", "UAT1")))
                .andExpect(jsonPath("$.hosts", contains("ccs1.cn.sit1", "ms1.cn.uat1", "ms2.cn.uat1")))
                .andExpect(jsonPath("$.propertyKeys", contains("mq.password", "spring.datasource.password", "token")));
        mockMvc.perform(get(BASE + "/records/filters").param("appSystem", "MS"))
                .andExpect(jsonPath("$.envInstances", contains("UAT1")))
                .andExpect(jsonPath("$.hosts", contains("ms1.cn.uat1", "ms2.cn.uat1")));
        mockMvc.perform(get(BASE + "/records/filters").param("envInstance", "SIT1"))
                .andExpect(jsonPath("$.hosts", contains("ccs1.cn.sit1")))
                .andExpect(jsonPath("$.propertyKeys", contains("token")));
        mockMvc.perform(get(BASE + "/records/filters").param("host", "ms2.cn.uat1"))
                .andExpect(jsonPath("$.hosts", hasSize(3))) // hosts are not narrowed by the host pick itself
                .andExpect(jsonPath("$.propertyKeys", contains("spring.datasource.password")));
    }

    @Test
    @DisplayName("saved-servers: the servers holding any listed property key; none listed is an empty list")
    void savedServers() throws Exception {
        seedForPaging();
        mockMvc.perform(get(BASE + "/records/saved-servers").param("propertyKey", "mq.password").param("propertyKey", "token"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].host", org.hamcrest.Matchers.containsInAnyOrder("ms1.cn.uat1", "ccs1.cn.sit1")));
        mockMvc.perform(get(BASE + "/records/saved-servers")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("update: 409 when it would collide with another row; 404 for an unknown id")
    void update() throws Exception {
        JsonNode saved = saveMsBoth();
        long first = saved.get("records").get(0).get("id").asLong();
        long second = saved.get("records").get(1).get("id").asLong();

        Map<String, Object> clash = obj(
                "appSystem", "MS", "host", "ms2.cn.uat1", "ip", "10.1.1.12",
                "propertyKey", "spring.datasource.password", "encryptedValue", VECTOR_MS1);
        mockMvc.perform(put(BASE + "/records/" + first).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(clash)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("duplicate_aes_record"))
                .andExpect(jsonPath("$.message").value(containsString("#" + second)));

        clash.put("propertyKey", "spring.datasource.username");
        clash.put("randomKey", "rotated");
        clash.put("note", "renamed");
        mockMvc.perform(put(BASE + "/records/" + first).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(clash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propertyKey").value("spring.datasource.username"))
                .andExpect(jsonPath("$.randomKey").value("rotated"))
                .andExpect(jsonPath("$.secretKey").value("rotatedms2.cn.uat110.1.1.12"))
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
    @DisplayName("save stores the IV and salt; decrypt records needs no input and ignores a supplied IV/salt")
    void decryptRecordsWithStoredIvAndSalt() throws Exception {
        JsonNode saved = saveMsBoth();
        JsonNode first = saved.get("records").get(0);
        assertThat(first.get("iv").asText()).isEqualTo(IV);
        assertThat(first.get("salt").asText()).isEqualTo(SALT);
        long ms1 = first.get("id").asLong();
        long ms2 = saved.get("records").get(1).get("id").asLong();

        postJson("/records/decrypt", obj("items", List.of(
                        obj("id", ms2),
                        // A wrong IV/salt in the request is ignored: the record's own are what it was encrypted with.
                        obj("id", ms1, "iv", "fedcba9876543210", "salt", "other"),
                        obj("id", ms1, "iv", IV, "salt", SALT),
                        obj("id", 999999))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                // ms1 is answered once.
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].plainValue").value(PLAIN))
                .andExpect(jsonPath("$[1].plainValue").value(PLAIN))
                .andExpect(jsonPath("$[2].error").value("not_found"));

        mockMvc.perform(get(BASE + "/records").param("appSystem", "MS"))
                .andExpect(jsonPath("$[0].iv").value(IV))
                .andExpect(jsonPath("$[0].salt").value(SALT));
    }

    @Test
    @DisplayName("records saved before V9 (no IV/salt) decrypt with the request's IV and salt, failures per row")
    void decryptLegacyRecordsWithSuppliedIvAndSalt() throws Exception {
        JsonNode saved = saveMsBoth();
        long ms1 = saved.get("records").get(0).get("id").asLong();
        long ms2 = saved.get("records").get(1).get("id").asLong();
        // What a row saved before V9 looks like.
        recordRepository.findAll().forEach(r -> {
            r.setIv(null);
            r.setSalt(null);
            recordRepository.save(r);
        });

        postJson("/records/decrypt", obj("items", List.of(obj("id", ms1, "iv", IV, "salt", SALT), obj("id", ms2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].plainValue").value(PLAIN))
                .andExpect(jsonPath("$[1].error").value("invalid_key_material"))
                .andExpect(jsonPath("$[1].message").value(org.hamcrest.Matchers.startsWith("iv:")));

        postJson("/records/decrypt", obj("items", List.of(
                        obj("id", ms1, "iv", "short", "salt", SALT),
                        obj("id", ms2, "iv", IV, "salt", "other"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].error").value("invalid_key_material"))
                .andExpect(jsonPath("$[1].error").value("decrypt_failed"));
    }

    // ------------------------------------------------------------------ batches

    @Test
    @DisplayName("encrypt/decrypt batch: every row against its own server, failures per row")
    void cryptoBatch() throws Exception {
        postJson("/encrypt/batch", obj("items", List.of(
                        obj("iv", IV, "salt", SALT, "randomKey", RANDOM, "host", "ms1.cn.uat1", "ip", "10.1.1.11", "value", PLAIN),
                        obj("iv", IV, "salt", SALT, "randomKey", RANDOM, "host", "ms2.cn.uat1", "ip", "10.1.1.12", "value", PLAIN),
                        obj("iv", "short", "salt", SALT, "randomKey", RANDOM, "host", "h", "ip", "1.1.1.1", "value", "x"),
                        obj("iv", IV, "randomKey", RANDOM, "host", "h", "ip", "1.1.1.1", "value", "x"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[3].error").value("invalid_key_material"))
                .andExpect(jsonPath("$[3].field").value("salt"))
                .andExpect(jsonPath("$[0].value").value(VECTOR_MS1))
                .andExpect(jsonPath("$[1].value").value(VECTOR_MS2))
                .andExpect(jsonPath("$[2].error").value("invalid_key_material"))
                .andExpect(jsonPath("$[2].field").value("iv"));

        String body = postJson("/decrypt/batch", obj("items", List.of(
                        obj("iv", IV, "salt", SALT, "randomKey", RANDOM, "host", "ms2.cn.uat1", "ip", "10.1.1.12", "value", VECTOR_MS1),
                        obj("iv", IV, "salt", SALT, "randomKey", RANDOM, "host", "ms1.cn.uat1", "ip", "10.1.1.11", "value", VECTOR_MS1))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].error").value("decrypt_failed"))
                .andExpect(jsonPath("$[1].value").value(PLAIN))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(IV, RANDOM, SALT);
    }

    @Test
    @DisplayName("save batch: items × servers in one call; a repeated property key or a bad IV is a 400, nothing written")
    void saveBatch() throws Exception {
        postJson("/records/batch", obj(
                "items", List.of(
                        obj("propertyKey", "db.password", "plainValue", PLAIN, "iv", IV, "salt", SALT, "randomKey", RANDOM),
                        obj("propertyKey", "mq.password", "plainValue", "mq", "iv", IV, "salt", SALT)),
                "servers", MS_BOTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(4))
                .andExpect(jsonPath("$.records", hasSize(4)))
                .andExpect(jsonPath("$.records[0].encryptedValue").value(VECTOR_MS1))
                .andExpect(jsonPath("$.records[1].encryptedValue").value(VECTOR_MS2))
                .andExpect(jsonPath("$.records[2].secretKey").value("ms1.cn.uat110.1.1.11"));

        postJson("/records/batch", obj(
                "items", List.of(
                        obj("propertyKey", "dup", "plainValue", "a", "iv", IV, "salt", SALT),
                        obj("propertyKey", " dup ", "plainValue", "b", "iv", IV, "salt", SALT)),
                "servers", MS_BOTH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("items[1].propertyKey"));

        postJson("/records/batch", obj(
                "items", List.of(
                        obj("propertyKey", "ok", "plainValue", "a", "iv", IV, "salt", SALT),
                        obj("propertyKey", "bad", "plainValue", "b", "iv", "short", "salt", SALT)),
                "servers", MS_BOTH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("items[1].iv"));

        postJson("/records/batch", obj(
                "items", List.of(obj("propertyKey", "nosalt", "plainValue", "a", "iv", IV)),
                "servers", MS_BOTH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("items[0].salt"));
        assertThat(recordRepository.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("save validation: no servers is a 400")
    void saveValidation() throws Exception {
        postJson("/records", obj("propertyKey", "k", "plainValue", "v", "iv", IV, "salt", SALT, "servers", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("servers"));
    }
}
