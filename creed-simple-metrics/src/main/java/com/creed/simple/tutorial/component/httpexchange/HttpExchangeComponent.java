package com.creed.simple.tutorial.component.httpexchange;

import org.apache.camel.Endpoint;
import org.apache.camel.support.DefaultComponent;

import java.util.Map;

/**
 * A custom Camel component that invokes a Spring {@code @HttpExchange} HTTP interface.
 *
 * <p>Registered under the {@code httpexchange:} scheme through
 * {@code META-INF/services/org/apache/camel/component/httpexchange}, which is how every
 * Camel component is discovered - no Spring bean definition required.
 *
 * <p>URI syntax:
 * <pre>
 *   httpexchange:beanName/methodName[?options]
 * </pre>
 * where {@code beanName} is the Spring bean holding the {@code @HttpExchange} proxy and
 * {@code methodName} is the interface method to call. See {@link HttpExchangeEndpoint} for
 * the options.
 */
public class HttpExchangeComponent extends DefaultComponent {

    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters)
            throws Exception {
        // remaining looks like "artisanClient/listUser"
        int slash = remaining.indexOf('/');
        if (slash <= 0 || slash == remaining.length() - 1) {
            throw new IllegalArgumentException(
                    "Invalid endpoint URI: " + uri + ". Expected httpexchange:beanName/methodName");
        }
        HttpExchangeEndpoint endpoint = new HttpExchangeEndpoint(uri, this,
                remaining.substring(0, slash), remaining.substring(slash + 1));
        setProperties(endpoint, parameters);
        return endpoint;
    }
}
