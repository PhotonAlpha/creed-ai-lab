package com.creed.resource.veracode.config;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * The Jira client's TLS, as asked: <b>no certificate and no hostname verification</b> - any server
 * certificate is accepted, self-signed, expired or issued for another name.
 *
 * <p>One no-op {@link X509ExtendedTrustManager} skips the certificate check, and - being the extended
 * kind - JSSE's endpoint identification as well, which runs inside {@code checkServerTrusted(chain,
 * authType, socket)}. HttpClient's own hostname check is turned off separately: {@code RemoteClientsConfiguration}
 * sets policy {@code CLIENT} with {@code NoopHostnameVerifier}, so the name is not checked on either side.
 *
 * <p>The price: anyone who can intercept the traffic can read the user's Jira token. Acceptable only on a
 * network the team trusts.
 */
final class TrustAllTls {

    private TrustAllTls() {
    }

    static SSLContext context() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new AcceptAll()}, new SecureRandom());
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot build the trust-all TLS context", e);
        }
    }

    private static final class AcceptAll extends X509ExtendedTrustManager {

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
