package com.creed.simple.web.ssl;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ssl.SslBundleRegistrar;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundleKey;
import org.springframework.boot.ssl.SslBundleRegistry;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.boot.ssl.jks.JksSslStoreBundle;
import org.springframework.boot.ssl.jks.JksSslStoreDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.KeyStore;
import java.security.KeyStoreException;

/**
 * Registers this service's mTLS {@link SslBundle}s <em>programmatically</em> instead of declaring them
 * under {@code spring.ssl.bundle.jks.*} in {@code application.yml}.
 *
 * <p><strong>Why not the YAML auto-config?</strong> Spring Boot only materialises a property-defined
 * bundle the first time something calls {@code SslBundles.getBundle(name)} — so a wrong keystore
 * password (or a missing/garbled PKCS12 file) stays invisible until the first HTTPS handshake, long
 * after the context is up. By building the bundles here and calling {@link SslBundle#createSslContext()}
 * during registration we force the keystore + truststore to load and the private key to be recovered
 * <em>at startup</em>: any problem surfaces immediately rather than at first use.
 *
 * <p><strong>The two bundles handle a validation failure differently:</strong>
 * <ul>
 *   <li>{@code creed-partner-server} — inbound HTTPS listener identity (serverAuth), bound by
 *       {@link TomcatHttpsConfiguration} via {@code creed.https.bundle}. <strong>Strict / fail-fast:</strong>
 *       a load failure propagates and <em>aborts startup</em>, so a developer must fix the server
 *       certificate before the service ever serves traffic.</li>
 *   <li>{@code creed-partner-client} — outbound mTLS identity (clientAuth) used by the
 *       {@code @LoadBalanced} RestClient, bound via {@code creed.partner.client-bundle}.
 *       <strong>Tolerant:</strong> a load failure is logged and the bundle is <em>skipped</em> (not
 *       registered) so the app still starts; {@code LoadBalancedRestClientConfiguration} then builds a
 *       non-mTLS (plain) connection manager — downstream HTTPS calls may fail the handshake at runtime,
 *       but the service boots.</li>
 * </ul>
 *
 * <p>Paths and passwords stay externalised (env-var overridable, same keys as before); the fixed
 * conventions (aliases, file names, PKCS12 type) live in code.
 */
@Configuration(proxyBeanMethods = false)
public class SslBundleConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SslBundleConfiguration.class);

    private static final String STORE_TYPE = "PKCS12";

    private final String rootPath;
    private final String keystorePassword;
    private final String truststorePassword;

    public SslBundleConfiguration(
            @Value("${creed.rootPath}") String rootPath,
            @Value("${CREED_ORDER_KEYSTORE_PASSWORD:changeit}") String keystorePassword,
            @Value("${CREED_ORDER_TRUSTSTORE_PASSWORD:changeit}") String truststorePassword) {
        this.rootPath = rootPath;
        this.keystorePassword = keystorePassword;
        this.truststorePassword = truststorePassword;
    }

    /**
     * Picked up by Spring Boot's {@code SslAutoConfiguration} and invoked while the {@code SslBundles}
     * registry bean is built — i.e. early in {@code refresh()}, so each bundle is validated at startup.
     */
    @Bean
    SslBundleRegistrar creedPartnerSslBundleRegistrar() {
        return (SslBundleRegistry registry) -> {
            // Inbound listener identity — STRICT: buildAndValidate throws on failure, aborting startup.
            registry.registerBundle("creed-partner-server", buildAndValidate(
                    "creed-partner-server",
                    "creed-gateway-partner",
                    "creed-gateway-partner-keystore.p12",
                    "creed-gateway-partner-truststore.p12"));

            // Outbound mTLS identity — TOLERANT: on failure we log and SKIP registration so the app still
            // starts; LoadBalancedRestClientConfiguration then falls back to a non-mTLS (plain) client.
            try {
                registry.registerBundle("creed-partner-client", buildAndValidate(
                        "creed-partner-client",
                        "creed-gateway-partner-cli",
                        "creed-gateway-partner-CLI-keystore.p12",
                        "creed-gateway-partner-CLI-truststore.p12"));
            } catch (RuntimeException ex) {
                log.error("SSL bundle 'creed-partner-client' is unavailable and will NOT be registered —"
                        + " the outbound RestClient falls back to a NON-mTLS client, so downstream HTTPS"
                        + " calls may fail the handshake at runtime. Cause: {}", ex.getMessage(), ex);
            }
        };
    }

    /**
     * Exposes the inbound listener's RSA key pair as a Nimbus {@link JWKSource} so the same PKCS12
     * material can also sign/verify JWTs (feed it to a {@code NimbusJwtEncoder}/{@code JwtDecoder}
     * or serve its public part as a JWKS endpoint via {@code JWKSet#toPublicJWKSet()}).
     *
     * <p>Reuses the <em>strict</em> {@code creed-partner-server} bundle — injecting {@link SslBundles}
     * (rather than reading the files again) guarantees the registrar above has run and the key
     * material already passed startup validation. {@link JWK#load} recovers the private key and cert
     * chain from the keystore: the resulting JWK carries the private key, the {@code x5c} chain, and
     * the keystore alias as its {@code kid}.
     */
    @Bean
    JWKSource<SecurityContext> jwkSource(SslBundles sslBundles) {
        SslBundle bundle = sslBundles.getBundle("creed-partner-server");
        SslBundleKey key = bundle.getKey();
        try {
            KeyStore keyStore = bundle.getStores().getKeyStore();
            char[] keyPassword = key.getPassword() != null ? key.getPassword().toCharArray() : null;
            JWK jwk = JWK.load(keyStore, key.getAlias(), keyPassword);
            if (jwk == null) {
                // JWK.load returns null (no exception) when the alias has no key entry.
                throw new IllegalStateException(
                        "SSL bundle 'creed-partner-server' keystore has no key entry for alias '"
                                + key.getAlias() + "'");
            }
            return new ImmutableJWKSet<>(new JWKSet(jwk));
        } catch (KeyStoreException | JOSEException ex) {
            throw new IllegalStateException(
                    "Cannot build JWKSource from SSL bundle 'creed-partner-server'"
                            + " (key alias '" + key.getAlias() + "'): " + ex.getMessage(),
                    ex);
        }
    }

    protected SslBundle buildAndValidate(String name, String keyAlias, String keystoreFile, String truststoreFile) {
        JksSslStoreDetails keystore = new JksSslStoreDetails(
                STORE_TYPE, null, "file:" + rootPath + "/" + keystoreFile, keystorePassword);
        JksSslStoreDetails truststore = new JksSslStoreDetails(
                STORE_TYPE, null, "file:" + rootPath + "/" + truststoreFile, truststorePassword);
        SslStoreBundle stores = new JksSslStoreBundle(keystore, truststore);
        // For these PKCS12 stores the private-key entry password equals the keystore password, so pass it
        // explicitly: createSslContext() will then actually recover the key and surface a wrong password.
        SslBundle bundle = SslBundle.of(stores, SslBundleKey.of(keystorePassword, keyAlias));

        try {
            // Forces keystore + truststore load and private-key recovery now — a bad password, missing
            // file, or unknown alias throws here.
            bundle.createSslContext();
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "SSL bundle '" + name + "' failed startup validation"
                            + " (check keystore/truststore path, password, type, or key alias '" + keyAlias + "'): "
                            + ex.getMessage(),
                    ex);
        }
        log.info("Validated SSL bundle '{}' at startup (keystore + truststore passwords and key alias '{}' OK)",
                name, keyAlias);
        return bundle;
    }
}
