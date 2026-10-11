package com.creed.gatewayproxy.service.splunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.creed.gatewayproxy.config.SplunkProperties;

class SecretCipherTest {

    private static final String KEY = "0123456789abcdef-master";

    @Test
    void roundTripsWithAFreshSaltAndIvEachTime() {
        SecretCipher cipher = new SecretCipher(KEY);
        String a = cipher.encrypt("p@ss 密码");
        String b = cipher.encrypt("p@ss 密码");
        assertThat(a).matches("ENC\\([0-9a-f]{16}:[0-9a-f]+\\)").isNotEqualTo(b);
        assertThat(SecretCipher.isEncrypted(a)).isTrue();
        assertThat(cipher.decrypt(a)).isEqualTo("p@ss 密码");
        assertThat(cipher.decrypt(" " + b + "\n")).isEqualTo("p@ss 密码");
    }

    @Test
    void wrongKeyOrTamperedValueFailsInsteadOfYieldingGarbage() {
        String value = new SecretCipher(KEY).encrypt("secret");
        assertThatThrownBy(() -> new SecretCipher("another-key-of-16+").decrypt(value))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("wrong CREED_SECRETS_KEY")
                .hasMessageNotContaining(value);
        String flipped = value.substring(0, value.length() - 2) + (value.charAt(value.length() - 2) == '0' ? '1' : '0') + ")";
        assertThatThrownBy(() -> new SecretCipher(KEY).decrypt(flipped)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretCipher(KEY).decrypt("plain")).hasMessageContaining("not an ENC");
    }

    @Test
    void shortKeyIsRejected() {
        assertThatThrownBy(() -> new SecretCipher("short")).hasMessageContaining("at least 16");
    }

    @Test
    void plainTextPasswordInTheTargetFailsWithoutEchoingIt() {
        assertThatThrownBy(() -> SplunkTestSupport.target("SIT", "https://sit:8000/", null, "hunter2-real"))
                .hasMessageContaining("targets[SIT].password must be ENC(")
                .hasMessageNotContaining("hunter2-real");
        String enc = new SecretCipher(KEY).encrypt("x");
        assertThat(SplunkTestSupport.target("SIT", "https://sit:8000/", null, enc).toString())
                .contains("ENC(***)").doesNotContain(enc.substring(4, 20));
    }

    private static SplunkProperties splunk(String sitPassword) {
        return SplunkTestSupport.splunk(true, true, null, "", List.of(
                SplunkTestSupport.target("SIT", "https://sit:8000/", null, sitPassword),
                SplunkTestSupport.target("UAT", "https://uat:8000/", null)));
    }

    @Test
    void secretsDecryptTheTargetPasswordWithTheMasterKey() {
        SplunkProperties props = splunk(new SecretCipher(KEY).encrypt("sit-pass"));
        MockEnvironment env = new MockEnvironment()
                .withProperty("CREED_SECRETS_KEY", KEY)
                .withProperty("ENV_MATRIX_TOTP_SECRET", "JBSWY3DPEHPK3PXP");
        SplunkSecrets secrets = new SplunkSecrets(env, props, q -> null);
        assertThat(secrets.password(props.targets().get(0))).isEqualTo("sit-pass");
        assertThat(secrets.password(props.targets().get(1))).isNull();
    }

    @Test
    void environmentOverridesTheEncryptedValueAndThenNoKeyIsNeeded() {
        SplunkProperties props = splunk(new SecretCipher(KEY).encrypt("sit-pass"));
        MockEnvironment env = new MockEnvironment().withProperty("SPLUNK_TARGET_SIT_PASSWORD", "from-env");
        SplunkSecrets secrets = new SplunkSecrets(env, props, q -> {
            assertThat(q).doesNotContain("CREED_SECRETS_KEY");
            return null;
        });
        assertThat(secrets.password(props.targets().get(0))).isEqualTo("from-env");
    }

    @Test
    void masterKeyIsPromptedForAndAMissingOrWrongOneFailsStartup() {
        SplunkProperties props = splunk(new SecretCipher(KEY).encrypt("sit-pass"));
        SplunkSecrets secrets = new SplunkSecrets(new MockEnvironment(), props, q -> q.contains("CREED_SECRETS_KEY") ? KEY : null);
        assertThat(secrets.password(props.targets().get(0))).isEqualTo("sit-pass");

        assertThatThrownBy(() -> new SplunkSecrets(new MockEnvironment(), props, q -> null))
                .hasMessageContaining("targets[SIT].password is ENC(...) but CREED_SECRETS_KEY was not supplied");
        MockEnvironment wrong = new MockEnvironment().withProperty("CREED_SECRETS_KEY", "a-different-master-key");
        assertThatThrownBy(() -> new SplunkSecrets(wrong, props, q -> null))
                .hasMessageContaining("targets[SIT].password: cannot decrypt");
    }

    @Test
    void encryptCommandReadsStdinWithoutATerminalAndPrintsOnlyTheValue() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = EncryptCommand.run(new MockEnvironment().withProperty("CREED_SECRETS_KEY", KEY), null,
                new ByteArrayInputStream("sit-pass\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        String value = out.toString(StandardCharsets.UTF_8).strip();
        assertThat(code).isZero();
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(new SecretCipher(KEY).decrypt(value)).isEqualTo("sit-pass");
    }

    @Test
    void encryptCommandOnATerminalAsksTwiceAndRejectsAMismatch() {
        ArrayDeque<String> answers = new ArrayDeque<>(List.of(KEY, KEY, "one", "two"));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = EncryptCommand.run(new MockEnvironment(), q -> answers.poll(), InputStreamNone.INSTANCE,
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(code).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("two passwords differ");
    }

    private static final class InputStreamNone extends ByteArrayInputStream {
        static final InputStreamNone INSTANCE = new InputStreamNone();

        private InputStreamNone() {
            super(new byte[0]);
        }
    }
}
