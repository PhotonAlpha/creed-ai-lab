package com.creed.simple.web;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundleKey;
import org.springframework.boot.ssl.SslBundleRegistry;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;

import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit tests for {@link SslBundleConfiguration} — no real keystore files are touched
 * (that end-to-end path is {@link SslBundleConfigurationTest}'s job). Coverage per method:
 * <ul>
 *   <li>{@code creedPartnerSslBundleRegistrar()} — a {@code spy} isolates {@code buildAndValidate}
 *       so the registrar's wiring is tested alone: both registrations with the conventional
 *       name/alias/file arguments, the STRICT server bundle propagating its failure, and the
 *       TOLERANT client bundle being skipped (logged, not registered, no exception).</li>
 *   <li>{@code jwkSource(SslBundles)} — the {@code SslBundles → SslBundle → key/stores} chain is
 *       mocked and {@link JWK#load} is static-mocked, covering: the happy path (wraps the loaded
 *       JWK in an {@link ImmutableJWKSet}), the null-JWK guard (alias without a key entry), the
 *       checked-exception wrap, and the null key password passed through as a null pin.</li>
 *   <li>{@code buildAndValidate(...)} — real method invoked against a nonexistent root path,
 *       asserting the diagnostic {@code IllegalStateException} contract (bundle name + alias hint
 *       + original cause preserved).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SslBundleConfigurationMockitoTest {

    private static final String STOREPASS = "changeit";
    private static final String SERVER_ALIAS = "creed-gateway-partner";

    @Mock
    private SslBundleRegistry registry;
    @Mock
    private SslBundles sslBundles;
    @Mock
    private SslBundle serverBundle;
    @Mock
    private SslBundle clientBundle;
    @Mock
    private SslBundleKey bundleKey;
    @Mock
    private SslStoreBundle storeBundle;

    private SslBundleConfiguration config;

    @BeforeEach
    void setUp() {
        config = spy(new SslBundleConfiguration("/creed/pki", STOREPASS, STOREPASS));
    }

    // ---------------------------------------------------------------- creedPartnerSslBundleRegistrar()

    @Test
    void registrarRegistersServerAndClientBundlesWithConventionalArguments() {
        doReturn(serverBundle).when(config).buildAndValidate(
                "creed-partner-server", SERVER_ALIAS,
                "creed-gateway-partner-keystore.p12", "creed-gateway-partner-truststore.p12");
        doReturn(clientBundle).when(config).buildAndValidate(
                "creed-partner-client", "creed-gateway-partner-cli",
                "creed-gateway-partner-CLI-keystore.p12", "creed-gateway-partner-CLI-truststore.p12");

        config.creedPartnerSslBundleRegistrar().registerBundles(registry);

        verify(registry).registerBundle("creed-partner-server", serverBundle);
        verify(registry).registerBundle("creed-partner-client", clientBundle);
    }

    @Test
    void strictServerBundleFailurePropagatesAndNothingIsRegistered() {
        doThrow(new IllegalStateException("SSL bundle 'creed-partner-server' failed startup validation"))
                .when(config).buildAndValidate(eq("creed-partner-server"), anyString(), anyString(), anyString());

        assertThatThrownBy(() -> config.creedPartnerSslBundleRegistrar().registerBundles(registry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("creed-partner-server");

        verify(registry, never()).registerBundle(anyString(), any(SslBundle.class));
    }

    @Test
    void tolerantClientBundleFailureIsSwallowedAndOnlyServerIsRegistered() {
        doReturn(serverBundle).when(config).buildAndValidate(
                eq("creed-partner-server"), anyString(), anyString(), anyString());
        doThrow(new IllegalStateException("SSL bundle 'creed-partner-client' failed startup validation"))
                .when(config).buildAndValidate(eq("creed-partner-client"), anyString(), anyString(), anyString());

        config.creedPartnerSslBundleRegistrar().registerBundles(registry);

        verify(registry).registerBundle("creed-partner-server", serverBundle);
        verify(registry, never()).registerBundle(eq("creed-partner-client"), any(SslBundle.class));
    }

    // ---------------------------------------------------------------- jwkSource(SslBundles)

    /** Wires the mocked {@code SslBundles → SslBundle → key/stores} chain and returns the keystore. */
    private KeyStore mockServerBundleChain(String keyPassword) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        when(sslBundles.getBundle("creed-partner-server")).thenReturn(serverBundle);
        when(serverBundle.getKey()).thenReturn(bundleKey);
        when(serverBundle.getStores()).thenReturn(storeBundle);
        when(storeBundle.getKeyStore()).thenReturn(keyStore);
        when(bundleKey.getAlias()).thenReturn(SERVER_ALIAS);
        when(bundleKey.getPassword()).thenReturn(keyPassword);
        return keyStore;
    }

    private static RSAKey sampleRsaJwk() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keyPair = generator.generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(SERVER_ALIAS)
                .build();
    }

    @Test
    void jwkSourceLoadsJwkFromBundleKeystoreAndWrapsItImmutably() throws Exception {
        KeyStore keyStore = mockServerBundleChain(STOREPASS);
        RSAKey rsaKey = sampleRsaJwk();

        try (MockedStatic<JWK> jwkStatic = mockStatic(JWK.class)) {
            jwkStatic.when(() -> JWK.load(any(KeyStore.class), anyString(), any(char[].class)))
                    .thenReturn(rsaKey);

            JWKSource<SecurityContext> source = config.jwkSource(sslBundles);

            // Loaded with exactly the bundle's keystore, alias, and password chars.
            jwkStatic.verify(() -> JWK.load(same(keyStore), eq(SERVER_ALIAS), aryEq(STOREPASS.toCharArray())));
            assertThat(source).isInstanceOf(ImmutableJWKSet.class);
            List<JWK> selected = source.get(
                    new JWKSelector(new JWKMatcher.Builder().keyType(KeyType.RSA).build()), null);
            assertThat(selected).containsExactly(rsaKey);
        }
    }

    @Test
    void jwkSourcePassesNullPinWhenTheBundleKeyHasNoPassword() throws Exception {
        KeyStore keyStore = mockServerBundleChain(null);

        try (MockedStatic<JWK> jwkStatic = mockStatic(JWK.class)) {
            jwkStatic.when(() -> JWK.load(any(KeyStore.class), anyString(), isNull()))
                    .thenReturn(sampleRsaJwk());

            config.jwkSource(sslBundles);

            jwkStatic.verify(() -> JWK.load(same(keyStore), eq(SERVER_ALIAS), isNull()));
        }
    }

    @Test
    void jwkSourceFailsFastWhenTheAliasHasNoKeyEntry() throws Exception {
        mockServerBundleChain(STOREPASS);

        try (MockedStatic<JWK> jwkStatic = mockStatic(JWK.class)) {
            // JWK.load's contract: null (not an exception) when the alias has no key entry.
            jwkStatic.when(() -> JWK.load(any(KeyStore.class), anyString(), any(char[].class)))
                    .thenReturn(null);

            assertThatThrownBy(() -> config.jwkSource(sslBundles))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no key entry")
                    .hasMessageContaining(SERVER_ALIAS);
        }
    }

    @Test
    void jwkSourceWrapsCheckedLoadFailuresWithAliasDiagnostics() throws Exception {
        mockServerBundleChain(STOREPASS);

        try (MockedStatic<JWK> jwkStatic = mockStatic(JWK.class)) {
            jwkStatic.when(() -> JWK.load(any(KeyStore.class), anyString(), any(char[].class)))
                    .thenThrow(new JOSEException("PKCS#12 entry not recoverable"));

            assertThatThrownBy(() -> config.jwkSource(sslBundles))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(SERVER_ALIAS)
                    .hasMessageContaining("PKCS#12 entry not recoverable")
                    .hasCauseInstanceOf(JOSEException.class);
        }
    }

    @Test
    void jwkSourceWrapsKeyStoreAccessFailures() throws Exception {
        mockServerBundleChain(STOREPASS);

        try (MockedStatic<JWK> jwkStatic = mockStatic(JWK.class)) {
            jwkStatic.when(() -> JWK.load(any(KeyStore.class), anyString(), any(char[].class)))
                    .thenThrow(new KeyStoreException("keystore not initialized"));

            assertThatThrownBy(() -> config.jwkSource(sslBundles))
                    .isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(KeyStoreException.class);
        }
    }

    // ---------------------------------------------------------------- buildAndValidate(...)

    @Test
    void buildAndValidateWrapsLoadFailureWithBundleNameAndAliasDiagnostics() {
        SslBundleConfiguration broken = new SslBundleConfiguration("/no/such/dir", STOREPASS, STOREPASS);

        assertThatThrownBy(() -> broken.buildAndValidate(
                "creed-partner-server", SERVER_ALIAS,
                "creed-gateway-partner-keystore.p12", "creed-gateway-partner-truststore.p12"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("creed-partner-server")
                .hasMessageContaining(SERVER_ALIAS)
                .hasCauseInstanceOf(RuntimeException.class);
    }
}
