package com.creed.gatewayproxy.service.splunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import com.creed.gatewayproxy.config.SplunkProperties;

class SplunkSecretsTest {

    private static SplunkProperties splunk(boolean enabled) {
        return SplunkTestSupport.splunk(enabled, true, null, "", List.of(
                SplunkTestSupport.target("SIT", "https://sit:8000/", null),
                SplunkTestSupport.target("uat", "https://uat:8000/", null)));
    }

    @Test
    void fileWinsOverVariableWhichWinsOverThePrompt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sit.txt");
        Files.writeString(file, "from-file\n");
        MockEnvironment env = new MockEnvironment()
                .withProperty("SPLUNK_TARGET_SIT_PASSWORD_FILE", file.toString())
                .withProperty("SPLUNK_TARGET_SIT_PASSWORD", "from-env")
                .withProperty("SPLUNK_TARGET_UAT_PASSWORD", "uat-env");
        List<String> asked = new ArrayList<>();
        SplunkSecrets secrets = new SplunkSecrets(env, splunk(true), q -> {
            asked.add(q);
            return q.contains("TOTP") ? "JBSWY3DPEHPK3PXP" : "typed";
        });
        SplunkProperties props = splunk(true);
        assertThat(secrets.password(props.targets().get(0))).isEqualTo("from-file"); // trimmed
        assertThat(secrets.password(props.targets().get(1))).isEqualTo("uat-env");  // id upper-cased into the name
        assertThat(asked).singleElement().asString().contains("ENV_MATRIX_TOTP_SECRET");
        assertThat(secrets.totpSecret()).isEqualTo("JBSWY3DPEHPK3PXP");
        assertThat(secrets.totpGenerated()).isFalse();
    }

    @Test
    void promptFillsWhatTheEnvironmentLacks() {
        List<String> asked = new ArrayList<>();
        SplunkSecrets secrets = new SplunkSecrets(new MockEnvironment(), splunk(true), q -> {
            asked.add(q);
            return q.contains("target SIT") ? "typed-sit" : null;
        });
        SplunkProperties props = splunk(true);
        assertThat(asked).hasSize(3);
        assertThat(asked.getFirst()).contains("SPLUNK_TARGET_SIT_PASSWORD", "admin @ https://sit:8000/");
        assertThat(secrets.password(props.targets().get(0))).isEqualTo("typed-sit");
        assertThat(secrets.password(props.targets().get(1))).isNull();
        assertThat(secrets.totpGenerated()).isTrue();
        assertThat(Totp.base32Decode(secrets.totpSecret())).hasSize(20);
    }

    @Test
    void mockModeAsksForNoSplunkPassword() {
        List<String> asked = new ArrayList<>();
        new SplunkSecrets(new MockEnvironment().withProperty("ENV_MATRIX_TOTP_SECRET", "JBSWY3DPEHPK3PXP"), splunk(false), q -> {
            asked.add(q);
            return null;
        });
        assertThat(asked).isEmpty();
    }
}
