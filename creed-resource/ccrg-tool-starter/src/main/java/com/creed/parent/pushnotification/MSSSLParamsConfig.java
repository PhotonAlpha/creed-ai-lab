package com.creed.parent.pushnotification;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

/**
 * This class used to read ssl configuration from gebngssl_ms.properties file.
 *
 */
@Component("msSSLParamsConfig")
@Configuration
@EnableConfigurationProperties
@ConditionalOnResource(resources = {"file:${ms.app.home.dir}/config/ssl/gebngssl_ms.properties"})
@PropertySource("file:${ms.app.home.dir}/config/ssl/gebngssl_ms.properties")
@Getter
@Setter
public class MSSSLParamsConfig {

  @Value("${ccs.common.crypto.initial.vector:}")
  private String initialVector;

  @Value("${ccs.common.crypto.salt:}")
  private String salt;

  @Value("${ccs.ms.server.ssl.enabled.protocols:}")
  private String[] msServerSslEnabledProtocols;

  @Value("${ccs.ms.server.ssl.ciphers.enabled:#{false}}")
  private boolean serverSslCiphersEnabled;

  @Value("${ccs.ms.server.ssl.ciphers:}")
  private String[] serverSslCiphers;

  @Value("${ccs.ms.server.ssl.random.key:}")
  private String randomKey;

  @Value("${ccs.ms.server.ssl.enabled:#{false}}")
  private boolean enabled;

  @Value("${ccs.ms.server.ssl.key-alias:}")
  private String keyAlias;

  @Value("${ccs.ms.server.ssl.key-password:}")
  private String keyPassword;

  @Value("${ccs.ms.server.ssl.key-store:}")
  private String keyStore;

  @Value("${ccs.ms.server.ssl.key-store-provider:}")
  private String keyStoreProvider;

  @Value("${ccs.ms.server.ssl.key-store-type:}")
  private String keyStoreType;

  @Value("${ccs.ms.server.ssl.trust-store-type:}")
  private String trustStoreType;

  @Value("${ccs.ms.server.ssl.trust-store:}")
  private String trustStore;

  @Value("${ccs.ms.server.ssl.trust-store-password:}")
  private String trustStorePassword;

  @Value("${ccs.ms.client.ssl.enabled.protocols:}")
  private String[] msClientSslEnabledProtocols;

  @Value("${ccs.ms.client.ssl.ciphers.enabled:#{false}}")
  private boolean clientSslCiphersEnabled;

  @Value("${ccs.ms.client.ssl.ciphers:}")
  private String[] clientSslCiphers;

  @Value("${ccs.ms.client.ssl.random.key:}")
  private String clientSslRandomKey;

  @Value("${ccs.ms.client.ssl.key-alias:}")
  private String clientSslKeyAlias;

  @Value("${ccs.ms.client.ssl.key-password:}")
  private String clientSslKeyPassword;

  @Value("${ccs.ms.client.ssl.key-store:}")
  private String clientSslKeyStore;

  @Value("${ccs.ms.client.ssl.key-store-provider:}")
  private String clientSslKeyStoreProvider;

  @Value("${ccs.ms.client.ssl.key-store-type:}")
  private String clientSslKeyStoreType;

  @Value("${ccs.ms.client.ssl.trust-store-type:}")
  private String clientSslTrustStoreType;

  @Value("${ccs.ms.client.ssl.trust-store:}")
  private String clientSslTrustStore;

  @Value("${ccs.ms.client.ssl.trust-store-password:}")
  private String clientSslTrustStorePassword;

  @Value("${ccs.ms.xms.client.ssl.enabled:#{false}}")
  private boolean xmsSslEnabled;

  @Value("${ccs.ms.pec.client.ssl.enabled:#{false}}")
  private boolean pecSslEnabled;

  @Value("${ccs.ms.export.client.ssl.enabled:#{false}}")
  private boolean exportSslEnabled;

  @Value("${ccs.ms.client-authentication.ssl.enabled:#{false}}")
  private boolean msClientAuthenticationEnabled;

  @Value("${ms.ca.ssl.trust-client:#{false}}")
  private Boolean clientNonAuth;

  @Value("${ccs.ms.common.server.ssl.disabled:#{false}}")
  private Boolean commonDisable;

  @Value("${ccs.ms.client.firebase.random.key:}")
  private String fireBaseRandomKey;

  @Value("${ccs.ms.client.firebase.nextgen.fcm.key:}")
  private String firebaseNextGenFcmKey;
  
  @Value("${ccs.ms.client.firebase.bizapp.random.key:}")
  private String fireBaseBizRandomKey;

  @Value("${ccs.ms.client.firebase.bizapp.fcm.key:}")
  private String firebaseBizFcmKey;
  

  //for fpx message check sum validation
  @Value("${ccs.ms.fpx.sendercert.alias:fpx}")
  private String fpxSenderCertAlias;

  @Value("${ccs.ms.fpx.key.store.path:}")
  private String fpxKeyStorePath;

  @Value("${ccs.ms.fpx.store.password:}")
  private String fpxKeyStorePassword;

  @Value("${ccs.ms.fpx.random.key:}")
  private String fpxRamdomKey;

  @Value("${ccs.ms.fpx.checksum.validation.enabled:#{false}}")
  private boolean fpxValidateChecksum;

  //for rpp message check sum validation
  @Value("${ccs.ms.rpp.sendercert.alias:rpp}")
  private String rppSenderCertAlias;

  @Value("${ccs.ms.rpp.key.store.path:}")
  private String rppKeyStorePath;

  @Value("${ccs.ms.rpp.store.password:}")
  private String rppKeyStorePassword;

  @Value("${ccs.ms.rpp.random.key:}")
  private String rppRamdomKey;

  @Value("${ccs.ms.rpp.checksum.validation.enabled:#{false}}")
  private boolean rppValidateChecksum;

  @Value("${ccs.ms.wam.client.ssl.enabled:#{false}}")
  private boolean wamSslEnabled;

  @Value("${ccs.ms.est.client.ssl.enabled:#{false}}")
  private boolean estSslEnabled;

  @Value("${ccs.ms.idb.notification-services.ssl.enabled:#{false}}")
  private boolean notificationServicesSSLEnabled;

}
