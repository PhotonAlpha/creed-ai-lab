package com.creed.simple.web.ssl;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.ssl.*;
import org.springframework.boot.ssl.jks.JksSslStoreBundle;
import org.springframework.boot.ssl.jks.JksSslStoreDetails;
import org.springframework.security.oauth2.jwt.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives {@link SslBundleConfiguration} against the real PKCS12 stores in {@code .support/scripts/pki}
 * (no Spring context — the registrar and the {@code jwkSource} factory method are exercised directly,
 * with {@link DefaultSslBundleRegistry} standing in for Boot's registry, as it implements both
 * {@code SslBundleRegistry} and {@code SslBundles}):
 * <ul>
 *   <li>registrar happy path — both bundles registered and validated;</li>
 *   <li>strict vs tolerant failure semantics — a broken server bundle aborts, missing client stores
 *       are skipped while the app (registry) survives;</li>
 *   <li>SslBundle → {@link JWKSource} conversion — key type, kid, private part, x5c chain;</li>
 *   <li>the JWKSource actually signing and verifying JWTs via Spring Security's Nimbus
 *       encoder/decoder, including rejection of a foreign key and of a tampered payload.</li>
 * </ul>
 */
class SslBundleConfigurationTest {

    private static final String STOREPASS = "changeit";
    private static final String SERVER_ALIAS = "creed-gateway-partner";

    private static String pkiDir;

    @BeforeAll
    static void locatePkiDir() {
        // Surefire runs with the module dir as working dir, IDEs may use the repo root — walk up.
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve(".support/scripts/pki"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as(".support/scripts/pki not found above " + Path.of("").toAbsolutePath()).isNotNull();
        pkiDir = dir.resolve(".support/scripts/pki").toString();
    }

    /** Builds the configuration and runs its registrar into a fresh registry. */
    private static DefaultSslBundleRegistry register(String rootPath, String keystorePassword) {
        DefaultSslBundleRegistry registry = new DefaultSslBundleRegistry();
        new SslBundleConfiguration(rootPath, keystorePassword, STOREPASS)
                .creedPartnerSslBundleRegistrar().registerBundles(registry);
        return registry;
    }

    private static JWKSource<SecurityContext> jwkSource() {
        DefaultSslBundleRegistry registry = register(pkiDir, STOREPASS);
        return new SslBundleConfiguration(pkiDir, STOREPASS, STOREPASS).jwkSource(registry);
    }

    private static RSAKey soleKey(JWKSource<SecurityContext> jwkSource) throws Exception {
        List<JWK> keys = jwkSource.get(new JWKSelector(new JWKMatcher.Builder().keyType(KeyType.RSA).build()), null);
        assertThat(keys).hasSize(1);
        return (RSAKey) keys.get(0);
    }

    // ---------------------------------------------------------------- bundle registration

    @Test
    void registersBothBundlesFromRealStores() {
        DefaultSslBundleRegistry registry = register(pkiDir, STOREPASS);

        assertThat(registry.getBundle("creed-partner-server")).isNotNull();
        assertThat(registry.getBundle("creed-partner-client")).isNotNull();
    }

    @Test
    void strictServerBundleFailureAbortsRegistration() {
        assertThatThrownBy(() -> register(pkiDir, "wrong-password"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("creed-partner-server");
    }

    @Test
    void tolerantClientBundleIsSkippedWhenItsStoresAreMissing(@TempDir Path tempDir) throws Exception {
        // Only the server stores exist here — the client (CLI) bundle must be skipped, not fatal.
        for (String file : List.of("creed-gateway-partner-keystore.p12", "creed-gateway-partner-truststore.p12")) {
            Files.copy(Path.of(pkiDir, file), tempDir.resolve(file));
        }

        DefaultSslBundleRegistry registry = register(tempDir.toString(), STOREPASS);

        assertThat(registry.getBundle("creed-partner-server")).isNotNull();
        assertThatThrownBy(() -> registry.getBundle("creed-partner-client"))
                .isInstanceOf(NoSuchSslBundleException.class);
    }

    // ---------------------------------------------------------------- SslBundle -> JWKSource

    @Test
    void jwkSourceExposesTheListenerKeyPairAsAnRsaJwk() throws Exception {
        RSAKey rsaKey = soleKey(jwkSource());

        assertThat(rsaKey.getKeyID()).isEqualTo(SERVER_ALIAS);          // kid = keystore alias
        assertThat(rsaKey.isPrivate()).isTrue();                        // signing needs the private part
        assertThat(rsaKey.getX509CertChain()).isNotEmpty();             // x5c from the keystore chain
        assertThat(rsaKey.toRSAPublicKey().getModulus().bitLength()).isEqualTo(2048);
    }

    @Test
    void jwkSourceFailsFastWhenTheAliasHasNoKeyEntry() {
        JksSslStoreDetails store = new JksSslStoreDetails(
                "PKCS12", null, "file:" + pkiDir + "/creed-gateway-partner-keystore.p12", STOREPASS);
        SslStoreBundle stores = new JksSslStoreBundle(store, store);
        DefaultSslBundleRegistry registry = new DefaultSslBundleRegistry();
        registry.registerBundle("creed-partner-server",
                SslBundle.of(stores, SslBundleKey.of(STOREPASS, "no-such-alias")));

        assertThatThrownBy(() ->
                new SslBundleConfiguration(pkiDir, STOREPASS, STOREPASS).jwkSource(registry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-such-alias");
    }

    // ---------------------------------------------------------------- encode / verify JWT

    private static Jwt encodeSampleJwt(JWKSource<SecurityContext> jwkSource) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("https://creed-simple-metrics.local")
                .subject("ethan")
                .claim("scope", "metrics.read")
                .issuedAt(now)
                .expiresAt(now.plus(5, ChronoUnit.MINUTES))
                .build();
        // No explicit JwsHeader: NimbusJwtEncoder defaults to RS256 and picks the sole RSA key.
        return new NimbusJwtEncoder(jwkSource).encode(JwtEncoderParameters.from(claims));
    }

    @Test
    void encodesJwtSignedByTheKeystoreKeyAndVerifiesItWithThePublicPart() throws Exception {
        JWKSource<SecurityContext> jwkSource = jwkSource();
        Jwt encoded = encodeSampleJwt(jwkSource);

        // Verify with ONLY the public half, the way a resource server would.
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(soleKey(jwkSource).toRSAPublicKey()).build();
        Jwt decoded = decoder.decode(encoded.getTokenValue());

        assertThat(decoded.getIssuer().toString()).isEqualTo("https://creed-simple-metrics.local");
        assertThat(decoded.getSubject()).isEqualTo("ethan");
        assertThat(decoded.getClaimAsString("scope")).isEqualTo("metrics.read");
        assertThat(decoded.getHeaders())
                .containsEntry("alg", "RS256")
                .containsEntry("kid", SERVER_ALIAS); // encoder propagates the JWK's kid
    }

    @Test
    void verificationRejectsTokenSignedByAForeignKey() throws Exception {
        Jwt encoded = encodeSampleJwt(jwkSource());

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPublicKey foreignPublicKey = (RSAPublicKey) generator.generateKeyPair().getPublic();
        NimbusJwtDecoder foreignDecoder = NimbusJwtDecoder.withPublicKey(foreignPublicKey).build();

        assertThatThrownBy(() -> foreignDecoder.decode(encoded.getTokenValue()))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void verificationRejectsTamperedPayload() throws Exception {
        JWKSource<SecurityContext> jwkSource = jwkSource();
        Jwt encoded = encodeSampleJwt(jwkSource);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(soleKey(jwkSource).toRSAPublicKey()).build();

        // Re-sign nothing: swap a claim in the payload segment and keep the original signature.
        String[] parts = encoded.getTokenValue().split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"sub\":\"ethan\"", "\"sub\":\"mallory\"");
        String tampered = parts[0]
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void name() {
        SHA256Digest sha256Digest = new SHA256Digest();
        byte[] randomValue = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
        sha256Digest.update(randomValue, 0, randomValue.length);
        // 3. Create a buffer to store the output (SHA-256 is 32 bytes)
        byte[] result = new byte[sha256Digest.getDigestSize()];
        // 4. Calculate the final hash value
        sha256Digest.doFinal(result, 0);
        // 5. Convert to Hex string for readability
        String hexString = Hex.toHexString(result);
        System.out.println("SHA-256 Hash: " + hexString.toUpperCase());
    }
}
