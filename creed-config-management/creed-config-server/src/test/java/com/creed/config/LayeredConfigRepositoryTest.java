package com.creed.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * The six-layer native repository: common → type → env-group → env → app → app-env.
 *
 * <p>Checked twice: the order of the property sources the server returns (highest precedence first),
 * and the values a real Spring Cloud Config client ends up with after merging them — placeholders
 * resolved across layers, {@code {cipher}} values decrypted.
 */
// classes= is needed: the nested @Configuration below would otherwise become the test's whole context.
@SpringBootTest(classes = CreedConfigServerApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"native", "test"})
class LayeredConfigRepositoryTest {

    private static final String REPO = "classpath:/config-repo/";

    @LocalServerPort
    private int port;

    private final TestRestTemplate rest = new TestRestTemplate("test-config", "test-password");

    private List<String> sources(String application, String profiles) throws Exception {
        ResponseEntity<String> response = rest.getForEntity(
                "http://localhost:" + port + "/config-server/" + application + "/" + profiles, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = new ObjectMapper().readTree(response.getBody());
        return json.get("propertySources").findValuesAsText("name").stream()
                .map(name -> name.replace(REPO, "").replace("/application.yml", ""))
                .toList();
    }

    @Test
    void allSixLayersStackHighestFirst() throws Exception {
        assertThat(sources("creed-resource-order", "ms,pdn,pdn")).containsExactly(
                "app-env/creed-resource-order/pdn",
                "app/creed-resource-order",
                "env/pdn",
                "env-group/pdn",
                "type/ms",
                "common");
    }

    @Test
    void layersWithoutADirectoryAreSkipped() throws Exception {
        // No app-env for order in sit1, and no type/sit or env-group/ms: only existing directories count.
        assertThat(sources("creed-resource-order", "ms,sit,sit1")).containsExactly(
                "app/creed-resource-order", "env/sit1", "env-group/sit", "type/ms", "common");
    }

    @Test
    void anAppWithNoAppDirectoryStillGetsTheSharedLayers() throws Exception {
        assertThat(sources("creed-resource-payment", "ms,uat,uat3")).containsExactly(
                "env/uat3", "env-group/uat", "type/ms", "common");
    }

    @Test
    void theExistingCloudClientsStillGetTheirAppFile() throws Exception {
        // creed-* modules' `cloud` profile sends no type/env: common + app, as before the layout.
        assertThat(sources("creed-gateway", "cloud")).containsExactly("app/creed-gateway", "common");
        assertThat(sources("application", "default")).containsExactly("common");
    }

    // ---- what a client actually resolves ----------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    static class ClientApp {
    }

    private ConfigurableApplicationContext client(String application, String configProfile, String... active) {
        return new SpringApplicationBuilder(ClientApp.class)
                .web(WebApplicationType.NONE)
                .profiles(active)
                .properties(Map.of(
                        // Not the server's application.yml, which is on this classpath too.
                        "spring.config.name", "no-local-config",
                        "spring.application.name", application,
                        "spring.config.import", "configserver:http://localhost:" + port + "/config-server",
                        "spring.cloud.config.profile", configProfile,
                        "spring.cloud.config.username", "test-config",
                        "spring.cloud.config.password", "test-password",
                        "spring.cloud.config.fail-fast", "true"))
                // The config-server jar on this classpath ships a listener that turns the config
                // client off in every app (ConfigServerBootstrapApplicationListener); a real client
                // has no such jar.
                .run("--spring.cloud.config.enabled=true");
    }

    @Test
    void anMsInSit1ResolvesEveryLayer() {
        try (ConfigurableApplicationContext ctx = client("creed-resource-order", "ms,sit,sit1")) {
            Environment env = ctx.getEnvironment();
            assertThat(env.getProperty("creed.config.layer")).isEqualTo("app");
            assertThat(env.getProperty("creed.config.layers.common")).isEqualTo("all");
            assertThat(env.getProperty("creed.config.layers.type")).isEqualTo("ms");
            assertThat(env.getProperty("creed.config.layers.env-group")).isEqualTo("sit");
            assertThat(env.getProperty("creed.config.layers.env")).isEqualTo("sit1");
            assertThat(env.getProperty("creed.config.layers.app")).isEqualTo("creed-resource-order");
            assertThat(env.getProperty("creed.env.group")).isEqualTo("sit");
            assertThat(env.getProperty("logging.level.com.creed")).isEqualTo("DEBUG");
            // A placeholder in the type layer, filled by the env layer, resolved on the client.
            assertThat(env.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"))
                    .isEqualTo("https://auth-sit1.creed.internal/auth-server");
            // {cipher} in the app layer arrives decrypted.
            assertThat(env.getProperty("creed.ssl.keystore-password")).isEqualTo("changeit");
        }
    }

    @Test
    void appEnvBeatsEverythingAndOnlyForItsEnvironment() {
        try (ConfigurableApplicationContext pdn = client("creed-resource-order", "ms,pdn,pdn");
             ConfigurableApplicationContext uat = client("creed-resource-order", "ms,uat,uat1")) {
            assertThat(pdn.getEnvironment().getProperty("creed.config.layer")).isEqualTo("app-env");
            assertThat(pdn.getEnvironment().getProperty("creed.http.read-timeout")).isEqualTo("5s");
            assertThat(pdn.getEnvironment().getProperty("server.tomcat.threads.max")).isEqualTo("400");
            assertThat(uat.getEnvironment().getProperty("creed.http.read-timeout")).isEqualTo("10s");
            assertThat(uat.getEnvironment().getProperty("server.tomcat.threads.max")).isEqualTo("200");
        }
    }

    @Test
    void theOrderHoldsWhenTheClientsActiveProfilesMatchTheSourceNames() {
        // The client tags a source "profile-specific" when its name contains "-<active profile>"
        // ("creed-gateway" contains "-gateway", "env-group" contains "-group"). The tag only ranks it
        // against SIBLING imports; inside one config-server response the server's order stands. If a
        // Spring Cloud upgrade ever changes that, these two fail.
        try (ConfigurableApplicationContext gateway =
                     client("creed-gateway", "gateway,uat,uat2", "gateway", "uat2");
             ConfigurableApplicationContext group = client("creed-resource-order", "ms,sit,sit1", "group")) {
            Environment env = gateway.getEnvironment();
            assertThat(env.getProperty("creed.config.layer")).isEqualTo("app-env");
            assertThat(env.getProperty("creed.gateway.downstream-response-timeout")).isEqualTo("45s");
            assertThat(env.getProperty("creed.http.read-timeout")).isEqualTo("30s");
            assertThat(env.getProperty("creed.env.name")).isEqualTo("uat2");
            assertThat(group.getEnvironment().getProperty("creed.config.layer")).isEqualTo("app");
        }
    }

    @Test
    void eachTypeGetsItsOwnTypeLayer() {
        try (ConfigurableApplicationContext proxy = client("creed-gateway-proxy", "proxy-gateway,nft,nft");
             ConfigurableApplicationContext aggregator = client("creed-simple-metrics", "aggregator-ms,sit,sit1")) {
            assertThat(proxy.getEnvironment().getProperty("creed.http.read-timeout")).isEqualTo("60s");
            assertThat(proxy.getEnvironment().getProperty("creed.env.name")).isEqualTo("nft");
            // proxy-gateway has no OAuth2 layer: the key is simply absent.
            assertThat(proxy.getEnvironment().getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"))
                    .isNull();
            assertThat(aggregator.getEnvironment().getProperty("creed.http.read-timeout")).isEqualTo("25s");
            assertThat(aggregator.getEnvironment().getProperty("creed.aggregator.fail-on-partial")).isEqualTo("true");
        }
    }
}
