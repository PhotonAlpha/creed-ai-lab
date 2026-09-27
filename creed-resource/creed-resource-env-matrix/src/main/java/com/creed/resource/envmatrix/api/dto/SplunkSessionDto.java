package com.creed.resource.envmatrix.api.dto;

import java.time.Instant;

/**
 * A brokered Splunk session.
 *
 * @param sourceCookie     the cookie read from Splunk's login response ({@code splunkd_8000})
 * @param cookieName       the name the script sets ({@code splunkd_8089})
 * @param cookieValue      the session value — a credential, sent with {@code Cache-Control: no-store}
 * @param script           ready to paste into the Splunk tab's devtools console
 * @param mode             {@code real} or {@code mock}; a mock value will not work in Splunk
 * @param correlationId    ties this response to its two audit rows
 * @param cookieFingerprint what the audit trail recorded in place of the value
 */
public record SplunkSessionDto(
        String sourceCookie,
        String cookieName,
        String cookieValue,
        String script,
        String mode,
        String correlationId,
        String cookieFingerprint,
        Instant issuedAt) {

    @Override
    public String toString() {
        return "SplunkSessionDto[cookieName=" + cookieName + ", mode=" + mode
                + ", correlationId=" + correlationId + ", cookieFingerprint=" + cookieFingerprint + "]";
    }
}
