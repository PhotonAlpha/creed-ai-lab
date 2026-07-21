package com.creed.simple.web;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CamelRestObservationConvention}: the {@code uri} tag is rewritten to the real
 * request path only for non-error {@code /camel/} requests (so each Camel endpoint is broken out instead
 * of collapsing into {@code UNKNOWN}); non-Camel paths and error responses keep the default tag.
 */
class CamelRestObservationConventionTest {

    private final CamelRestObservationConvention convention = new CamelRestObservationConvention();

    private String uriTag(String uri, int status) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);

        ServerRequestObservationContext context = new ServerRequestObservationContext(request, response);
        KeyValues keyValues = convention.getLowCardinalityKeyValues(context);
        return keyValues.stream()
                .filter(kv -> "uri".equals(kv.getKey()))
                .map(KeyValue::getValue)
                .findFirst()
                .orElse(null);
    }

    @Test
    void camelPathWithSuccessStatusGetsTheRealPathAsUri() {
        assertThat(uriTag("/camel/api/fulfillment", 200)).isEqualTo("/camel/api/fulfillment");
    }

    @Test
    void camelPathWithErrorStatusKeepsTheDefaultTag() {
        // 404-probing bogus paths must not explode series cardinality: the path is NOT substituted,
        // so the default convention's error tag (NOT_FOUND) survives instead of the real path.
        assertThat(uriTag("/camel/api/does-not-exist", 404)).isEqualTo("NOT_FOUND");
    }

    @Test
    void nonCamelPathIsLeftWithTheDefaultTag() {
        assertThat(uriTag("/actuator/health", 200)).isEqualTo("UNKNOWN");
    }

    @Test
    void nullCarrierReturnsSuperKeyValuesUnchanged() {
        // A context with no carrier must not NPE — it just returns the default key values.
        ServerRequestObservationContext context =
                new ServerRequestObservationContext(new MockHttpServletRequest(), new MockHttpServletResponse());
        context.setCarrier(null);
        assertThat(convention.getLowCardinalityKeyValues(context)).isNotNull();
    }
}
