package com.creed.simple.tutorial.client;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;
import java.util.Map;

/**
 * A Spring HTTP interface: the declarative alternative to hand-writing an HTTP call.
 *
 * <p>Compared with the {@code http://} Camel component used by API-3, the contract here is
 * <b>compile-time checked</b> - the URL, the verb and the response type all live in one place.
 * No implementation is written: {@code HttpServiceProxyFactory} generates the proxy, and the
 * {@code httpexchange:} Camel component invokes it.
 *
 * <p><b>Request bodies are JSON strings.</b> Every {@code @RequestBody} is a {@code String} holding
 * the JSON exactly as the request template rendered it, so the template alone decides what goes
 * on the wire and no request DTO is needed. The type-level {@code contentType} is what makes it
 * JSON: with a String body Spring picks the String converter, which writes the text as-is and
 * would label it {@code text/plain} otherwise.
 *
 * <p><b>Headers are not declared per method.</b> Whatever is set at the type level below is sent
 * by every method; per-request values (trace id, channel) are added for every method in one place
 * by {@link ArtisanHeaders}, an interceptor on the client's RestClient. So no method carries an
 * {@code @RequestHeader} parameter, and adding a method needs no header plumbing at all.
 *
 * <p>The {@code ${...}} placeholders resolve because {@code HttpServiceClientConfig} wires Spring's
 * embedded value resolver into the proxy factory; both {@code url} and {@code headers} support them.
 */
@HttpExchange(url = "${creed.tutorial.remote.base-url}/mock/artisan",
              contentType = MediaType.APPLICATION_JSON_VALUE,
              accept = MediaType.APPLICATION_JSON_VALUE,
              headers = "X-Client-Id=${spring.application.name}")
public interface ArtisanClient {

    /** All accounts of a user; used by API-7 {@code /accounts}. */
    @PostExchange("list")
    ResponseEntity<List<MyAccountDetailsVO>> listUser(@RequestBody String userJson,
                                                      @RequestParam Map<String, String> params);

    /** Opens an account and returns it with the id the remote system assigned; API-7 {@code /accounts/create}. */
    @PostExchange("create")
    ResponseEntity<MyAccountDetailsVO> createUser(@RequestBody String userJson);

    /** One account by id ({@code {"userId": "..."}}); 404 when unknown. API-7 {@code /accounts/detail}. */
    @PostExchange("detail")
    ResponseEntity<MyAccountDetailsVO> getUser(@RequestBody String queryJson);
}
