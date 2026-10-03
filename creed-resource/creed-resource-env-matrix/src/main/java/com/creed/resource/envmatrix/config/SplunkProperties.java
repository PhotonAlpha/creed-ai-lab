package com.creed.resource.envmatrix.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code env-matrix.splunk.*} — the shared Splunk account the broker logs in with.
 *
 * @param enabled          the switch for the real call. {@code false} (default): the client returns a
 *                         fabricated cookie and touches no network, so the page and the audit trail
 *                         work with no Splunk to reach. {@code true}: POST to {@code loginUrl} for real.
 * @param loginUrl         Splunk Web's form login, e.g. {@code https://splunk:8000/en-US/account/login}
 * @param username         the shared account
 * @param password         its password — override with {@code SPLUNK_PASSWORD} or a {@code {cipher}}
 *                         value from the config server rather than committing it
 * @param sessionCookie    the cookie read from the login response ({@code splunkd_<web port>})
 * @param scriptCookieName the cookie name written into the copyable {@code document.cookie} script.
 *                         Separate from {@code sessionCookie} because the requirement asks for the
 *                         value read from {@code splunkd_8000} to be set as {@code splunkd_8089}.
 * @param scriptCookiePath {@code path=} of that script
 * @param prefetchCval     GET the login page first and send back its {@code cval} cookie/field.
 *                         Splunk Web 7+ rejects a login POST without it; harmless where it is unused.
 * @param connectTimeout   TCP connect timeout
 * @param readTimeout      whole-request timeout
 * @param sslBundle        optional Spring SSL bundle whose truststore validates Splunk's certificate.
 *                         Blank ⇒ the JDK's default trust store. Note that declaring a bundle opts it
 *                         into Boot's SslMeterBinder, which opens it at startup (see the platform skill).
 */
@ConfigurationProperties("env-matrix.splunk")
public record SplunkProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("https://splunk.example.invalid:8000/en-US/account/login") String loginUrl,
        @DefaultValue("admin") String username,
        @DefaultValue("admin") String password,
        @DefaultValue("splunkd_8000") String sessionCookie,
        @DefaultValue("splunkd_8089") String scriptCookieName,
        @DefaultValue("/") String scriptCookiePath,
        @DefaultValue("true") boolean prefetchCval,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("15s") Duration readTimeout,
        @DefaultValue("") String sslBundle) {

    /** {@code real} or {@code mock} — shown on the page and in the audit trail. */
    public String mode() {
        return enabled ? "real" : "mock";
    }

    /** Whether a login can be attempted; the mock needs nothing. */
    public boolean configured() {
        return !enabled || (!loginUrl.isBlank() && !username.isBlank() && !password.isBlank());
    }

    /** The password is a credential — never let it reach a log line via the record's default toString. */
    @Override
    public String toString() {
        return "SplunkProperties[enabled=" + enabled + ", loginUrl=" + loginUrl + ", username=" + username
                + ", password=" + (password.isBlank() ? "<unset>" : "***")
                + ", sessionCookie=" + sessionCookie + ", scriptCookieName=" + scriptCookieName
                + ", prefetchCval=" + prefetchCval + ", sslBundle=" + sslBundle + "]";
    }
}
