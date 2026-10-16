package com.creed.simple.web;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class JsessionIdStrippingInterceptorTest {

    private static final String JSESSIONID = "JSESSIONID=8F3A1C; Path=/api/order; HttpOnly";
    private static final String WSASID =
            "WSASID=abc123; Path=/; Domain=localhost; Max-Age=1800; Expires=Tue, 13 Oct 2026 10:00:00 GMT";
    private static final String THEME = "theme=dark; Path=/";

    private final JsessionIdStrippingInterceptor interceptor = new JsessionIdStrippingInterceptor();

    private HttpHeaders intercept(String... setCookies) throws Exception {
        MockClientHttpResponse downstream = new MockClientHttpResponse("{}".getBytes(), HttpStatus.OK);
        downstream.getHeaders().add("X-Trace", "t1");
        for (String c : setCookies) downstream.getHeaders().add(HttpHeaders.SET_COOKIE, c);
        return interceptor.intercept(new MockClientHttpRequest(POST, URI.create("https://order-resource/s")),
                new byte[0], (req, body) -> downstream).getHeaders();
    }

    @Test
    void removesOnlyJsessionIdAndKeepsTheRestInOrder() throws Exception {
        HttpHeaders headers = intercept(WSASID, JSESSIONID, THEME);

        assertThat(headers.get(HttpHeaders.SET_COOKIE)).containsExactly(WSASID, THEME);
        assertThat(headers.getFirst("X-Trace")).isEqualTo("t1");
    }

    @Test
    void dropsTheHeaderWhenJsessionIdWasTheOnlyCookie() throws Exception {
        assertThat(intercept(JSESSIONID).containsHeader(HttpHeaders.SET_COOKIE)).isFalse();
    }

    @Test
    void leavesResponsesWithoutJsessionIdUntouched() throws Exception {
        assertThat(intercept(WSASID, THEME).get(HttpHeaders.SET_COOKIE)).containsExactly(WSASID, THEME);
        assertThat(intercept().containsHeader(HttpHeaders.SET_COOKIE)).isFalse();
    }

    @Test
    void matchesTheCookieNameExactly() {
        assertThat(JsessionIdStrippingInterceptor.isJsessionId(" JSESSIONID =x")).isTrue();
        assertThat(JsessionIdStrippingInterceptor.isJsessionId("JSESSIONID2=x")).isFalse();
        assertThat(JsessionIdStrippingInterceptor.isJsessionId("jsessionid=x")).isFalse();
        assertThat(JsessionIdStrippingInterceptor.isJsessionId("X=JSESSIONID=y")).isFalse();
        assertThat(JsessionIdStrippingInterceptor.isJsessionId("JSESSIONID")).isFalse();
    }

    @Test
    void theCookieRelayBuilderAppliesIt() {
        RestClient.Builder builder = new CookieRelayRestClientConfiguration()
                .cookieRelayRestClientBuilder(new SimpleClientHttpRequestFactory(), ObservationRegistry.NOOP);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://order-resource/api/order/session"))
                .andRespond(withSuccess().header(HttpHeaders.SET_COOKIE, JSESSIONID, WSASID));

        ResponseEntity<Void> entity = builder.build().post()
                .uri("https://order-resource/api/order/session").retrieve().toBodilessEntity();

        assertThat(entity.getHeaders().get(HttpHeaders.SET_COOKIE)).containsExactly(WSASID);
        server.verify();
    }
}
