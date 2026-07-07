package com.creed.simple.route;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.HttpCookie;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reproduces, against a real downstream cluster, the {@code $Path="/"; $Domain=...} Cookie-header
 * corruption seen in production wire logs:
 *
 * <ol>
 *   <li>{@code POST <cluster>/session} — the downstream issues JSESSIONID (Path only) and WSASID
 *       (Path + Domain + Max-Age, <b>no</b> Expires) via Spring {@code ResponseCookie};</li>
 *   <li>{@code java.net.HttpCookie.parse()} sees "Max-Age without Expires" and guesses cookie
 *       <b>version 1</b> (RFC 2965) for WSASID, while JSESSIONID stays version 0;</li>
 *   <li>the BUGGY forward joins {@code HttpCookie.toString()} — for a version-1 cookie that emits
 *       {@code name="value";$Path="/";$Domain="..."} — into {@code Cookie} via
 *       {@code header(HttpHeaders.COOKIE, ...)}: watch {@code org.apache.hc.client5.http.wire};</li>
 *   <li>the FIXED forward joins plain {@code name=value} pairs — the clean header.</li>
 * </ol>
 *
 * <p>Both echoes return what the downstream Tomcat actually parsed, so the response also shows the
 * damage server-side ({@code $Path}/{@code $Domain} arriving as cookies of their own).
 *
 * <p>Referenced from {@code camel-context.xml} as {@code <process ref="cookieRelayProcessor"/>};
 * the {@code cluster} query parameter ({@code order}, default, or {@code catalog}) picks the
 * downstream cluster. Uses the cookie-management-disabled {@code cookieRelayRestClient} — see
 * {@link com.creed.simple.web.CookieRelayRestClientConfiguration} for why the business client
 * would mask the bug.
 */
@Slf4j
@Component("cookieRelayProcessor")
public class CookieRelayProcessor implements Processor {

    private final RestClient cookieRelayRestClient;

    public CookieRelayProcessor(@Qualifier("cookieRelayRestClient") RestClient cookieRelayRestClient) {
        this.cookieRelayRestClient = cookieRelayRestClient;
    }

    @Override
    public void process(Exchange exchange) {
        String cluster = exchange.getMessage().getHeader("cluster", "order", String.class);
        String base = "catalog".equals(cluster)
                ? "https://catalog-resource/api/catalog"
                : "https://order-resource/api/order";

        // 1) "login": the downstream sets JSESSIONID (Path only) + WSASID (Path/Domain/Max-Age).
        ResponseEntity<JsonNode> session = cookieRelayRestClient.post()
                .uri(base + "/session")
                .retrieve()
                .toEntity(JsonNode.class);
        List<String> setCookies = session.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE);

        // 2) THE BUG: Max-Age without Expires -> HttpCookie.parse() guesses version 1 (RFC 2965),
        //    and HttpCookie.toString() then serializes  name="value";$Path="/";$Domain="..."
        List<HttpCookie> parsed = setCookies.stream()
                .flatMap(header -> HttpCookie.parse(header).stream())
                .toList();
        String buggyCookieHeader = parsed.stream()
                .map(HttpCookie::toString)
                .collect(Collectors.joining("; "));
        String fixedCookieHeader = parsed.stream()
                .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                .collect(Collectors.joining("; "));
        log.info("[cookie-relay] buggy Cookie header: {}", buggyCookieHeader);
        log.info("[cookie-relay] fixed Cookie header: {}", fixedCookieHeader);

        // 3) forward both variants — compare the two outgoing "Cookie:" lines in the wire log.
        JsonNode buggyEcho = echo(base, buggyCookieHeader);
        JsonNode fixedEcho = echo(base, fixedCookieHeader);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cluster", cluster);
        result.put("setCookiesFromSession", setCookies);
        result.put("parsedCookies", parsed.stream()
                .map(cookie -> Map.of(
                        "name", cookie.getName(),
                        "guessedVersion", cookie.getVersion(),
                        "toString", cookie.toString()))
                .toList());
        result.put("buggyCookieHeader", buggyCookieHeader);
        result.put("buggyEcho", buggyEcho);
        result.put("fixedCookieHeader", fixedCookieHeader);
        result.put("fixedEcho", fixedEcho);
        exchange.getMessage().setBody(result);
    }

    private JsonNode echo(String base, String cookieHeader) {
        return cookieRelayRestClient.post()
                .uri(base + "/echo")
                .header(HttpHeaders.COOKIE, cookieHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("probe", "cookie-relay"))
                .retrieve()
                .body(JsonNode.class);
    }
}
