package com.creed.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"native", "test"})
@EnableConfigurationProperties
class CreedConfigServerApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RestTemplateBuilder restTemplateBuilder;

    @Value("${local.server.port}")
    private int port;

    @Test
    void contextLoadsConfigServerInfrastructure() {
        // @EnableConfigServer wires this @ConfigurationProperties bean (registered under a
        // derived name, so assert by type); absence means the annotation was lost.
        assertThat(context.getBeanNamesForType(
                org.springframework.cloud.config.server.config.ConfigServerProperties.class))
                .isNotEmpty();
        // Security chain is wired.
        assertThat(context.getBeanNamesForType(org.springframework.security.web.SecurityFilterChain.class))
                .isNotEmpty();
    }

    @Test
    void httpClientBeansDisabledInTestProfile() {
        // creed.http-client.enabled=false in application-test.yml — bundle-aware
        // RestTemplate/RestClient beans must NOT be created (no cert files in tests).
        assertThat(context.getBeanNamesForType(org.springframework.web.client.RestTemplate.class))
                .doesNotContain("mtlsRestTemplate");
        assertThat(context.getBeanNamesForType(org.springframework.web.client.RestClient.class))
                .doesNotContain("mtlsRestClient");
    }

    @Test
    void configEndpointRequiresAuth() {
        TestRestTemplate anonymous = new TestRestTemplate(restTemplateBuilder);
        ResponseEntity<String> response = anonymous.getForEntity(
                "http://localhost:" + port + "/config-server/application/default", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void configEndpointServesNativeRepoWhenAuthenticated() {
        TestRestTemplate authed = new TestRestTemplate("test-config", "test-password");
        ResponseEntity<String> response = authed.getForEntity(
                "http://localhost:" + port + "/config-server/application/default", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Sample served by src/main/resources/config-repo/application.yml
        assertThat(response.getBody()).contains("hello from creed-config-server");
    }

    @Test
    void actuatorHealthIsPublic() {
        TestRestTemplate anonymous = new TestRestTemplate(restTemplateBuilder);
        ResponseEntity<String> response = anonymous.getForEntity(
                "http://localhost:" + port + "/config-server/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
