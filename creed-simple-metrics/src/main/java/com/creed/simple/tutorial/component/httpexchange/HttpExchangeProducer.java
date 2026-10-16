package com.creed.simple.tutorial.component.httpexchange;

import tools.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.support.DefaultProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Does the actual work of the {@code httpexchange:} component.
 *
 * <p>One exchange goes through four steps, none of which needs a ProducerTemplate:
 * <ol>
 *   <li><b>render</b> the request template, so the outbound payload is described in a
 *       {@code .ftl} file rather than in Java;</li>
 *   <li><b>bind</b> the rendered payload plus the Exchange headers onto the interface
 *       method's parameters, guided by the Spring annotations already on them;</li>
 *   <li><b>invoke</b> the {@code @HttpExchange} proxy - Spring performs the HTTP call and
 *       deserialises the reply into the declared return type;</li>
 *   <li><b>render</b> the response template, so the reply is mapped back into this
 *       application's own shape.</li>
 * </ol>
 *
 * <p>Parameter binding follows the annotations that are on the interface anyway:
 * <table>
 *   <tr><th>Parameter</th><th>Bound from</th></tr>
 *   <tr><td>{@code @RequestBody}</td><td>the rendered request template (or the message body);
 *       verbatim for a {@code String} parameter, parsed for any other type</td></tr>
 *   <tr><td>{@code @RequestHeader} on a Map / MultiValueMap</td><td>Exchange headers</td></tr>
 *   <tr><td>{@code @RequestParam} on a Map</td><td>the Exchange header named by {@code queryParamsHeader}</td></tr>
 *   <tr><td>{@code @PathVariable}</td><td>the Exchange header of the same name</td></tr>
 * </table>
 *
 * <p>Headers are better not declared per method at all: the proxy is invoked inside
 * {@link HttpExchangeContext}, so an interceptor on the RestClient behind the proxy can read the
 * Exchange and add headers to every method in one place.
 * {@code @RequestHeader Map} binding remains for the occasional method that needs its own.
 */
public class HttpExchangeProducer extends DefaultProducer {

    private static final Logger log = LoggerFactory.getLogger(HttpExchangeProducer.class);

    /** Headers that belong to Camel itself and must never leak onto the wire. */
    private static final Set<String> SKIPPED_HEADER_PREFIXES = Set.of("Camel", "camel", "breadcrumb");

    private final HttpExchangeEndpoint endpoint;
    private final FreemarkerRenderer renderer = new FreemarkerRenderer();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Object client;
    private Method method;

    public HttpExchangeProducer(HttpExchangeEndpoint endpoint) {
        super(endpoint);
        this.endpoint = endpoint;
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();
        // Resolve once at route start: a missing bean or method should fail fast, not per message.
        this.client = endpoint.getCamelContext().getRegistry().lookupByName(endpoint.getBeanName());
        if (client == null) {
            throw new IllegalStateException(
                    "No bean named '" + endpoint.getBeanName() + "' found in the registry");
        }
        List<Method> candidates = findMethods(client.getClass(), endpoint.getMethodName());
        if (candidates.isEmpty()) {
            throw new IllegalStateException("Bean '" + endpoint.getBeanName() + "' ("
                    + client.getClass().getName() + ") has no method '" + endpoint.getMethodName() + "'");
        }
        if (candidates.size() > 1) {
            throw new IllegalStateException("Method '" + endpoint.getMethodName() + "' on bean '"
                    + endpoint.getBeanName() + "' is overloaded; this component needs a unique name");
        }
        this.method = candidates.get(0);
        log.info("[httpexchange] bound {}/{} -> {}", endpoint.getBeanName(), endpoint.getMethodName(), method);
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        // ---- 1. render the request template ---------------------------------------------
        Object payload = exchange.getIn().getBody();
        if (endpoint.getRequestTemplate() != null) {
            String rendered = renderer.render(endpoint.getRequestTemplate(), payload, exchange);
            log.info("[httpexchange][{}] request template {} rendered:\n{}",
                    endpoint.getMethodName(), endpoint.getRequestTemplate(), rendered.trim());
            payload = rendered;
        }

        // ---- 2. bind the method parameters -----------------------------------------------
        Object[] args = bindArguments(exchange, payload);

        // ---- 3. invoke the @HttpExchange proxy -------------------------------------------
        log.info("[httpexchange][{}] invoking {}.{}", endpoint.getMethodName(),
                endpoint.getBeanName(), method.getName());
        // The Exchange is visible to ArtisanHeaders (the header policy interceptor) for the duration of the call
        Object result = HttpExchangeContext.callWith(exchange, () -> method.invoke(client, args));

        // Unwrap ResponseEntity so templates and downstream steps see the payload, not the envelope
        if (result instanceof ResponseEntity<?> entity) {
            exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, entity.getStatusCode().value());
            result = entity.getBody();
        }

        // ---- 4. render the response template ---------------------------------------------
        Object body = result;
        if (endpoint.isNormalizeResponse() && body != null) {
            // Turn POJOs/records into plain Maps and Lists so the template never needs getters
            body = objectMapper.convertValue(body, Object.class);
        }
        if (endpoint.getResponseTemplate() != null) {
            String rendered = renderer.render(endpoint.getResponseTemplate(), body, exchange);
            log.info("[httpexchange][{}] response template {} rendered:\n{}",
                    endpoint.getMethodName(), endpoint.getResponseTemplate(), rendered.trim());
            body = rendered;
        }
        if (endpoint.getUnmarshalTo() != null && body instanceof String text) {
            body = objectMapper.readValue(text, Class.forName(endpoint.getUnmarshalTo()));
        }
        exchange.getIn().setBody(body);
    }

    // ---------------------------------------------------------------- method lookup

    /**
     * Finds the method to invoke, <b>preferring the interface declaration</b>.
     *
     * <p>This matters more than it looks. {@code HttpServiceProxyFactory} hands back a JDK
     * dynamic proxy, and a proxy class's generated methods carry <b>no parameter annotations</b>.
     * Looking the method up on {@code proxy.getClass()} therefore finds a signature where
     * {@code @RequestBody}, {@code @RequestHeader} and {@code @RequestParam} have all vanished,
     * every argument binds to null, and Spring fails with "RequestBody is required".
     * Resolving against the interface keeps the annotations; invoking an interface
     * {@link Method} on the proxy instance works exactly the same.
     */
    private static List<Method> findMethods(Class<?> type, String name) {
        Map<String, Method> unique = new LinkedHashMap<>();
        for (Class<?> itf : collectInterfaces(type)) {
            for (Method m : itf.getMethods()) {
                if (m.getName().equals(name)) {
                    unique.putIfAbsent(Arrays.toString(m.getParameterTypes()), m);
                }
            }
        }
        if (unique.isEmpty()) {
            // Not a proxy (or the method is declared on the class itself)
            for (Method m : type.getMethods()) {
                if (m.getName().equals(name)) {
                    unique.putIfAbsent(Arrays.toString(m.getParameterTypes()), m);
                }
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static Set<Class<?>> collectInterfaces(Class<?> type) {
        Set<Class<?>> found = new LinkedHashSet<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            collectInterfaces(c.getInterfaces(), found);
        }
        return found;
    }

    private static void collectInterfaces(Class<?>[] interfaces, Set<Class<?>> found) {
        for (Class<?> itf : interfaces) {
            if (found.add(itf)) {
                collectInterfaces(itf.getInterfaces(), found);
            }
        }
    }

    // ---------------------------------------------------------------- parameter binding

    private Object[] bindArguments(Exchange exchange, Object payload) throws Exception {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter p = parameters[i];
            if (p.isAnnotationPresent(RequestBody.class)) {
                args[i] = convertBody(payload, p.getType());
            } else if (p.isAnnotationPresent(RequestHeader.class)
                    && Map.class.isAssignableFrom(p.getType())) {
                MultiValueMap<String, String> headers = buildHeaders(exchange);
                args[i] = MultiValueMap.class.isAssignableFrom(p.getType())
                        ? headers : headers.toSingleValueMap();
            } else if (p.isAnnotationPresent(RequestParam.class)
                    && Map.class.isAssignableFrom(p.getType())) {
                args[i] = buildQueryParams(exchange);
            } else if (p.isAnnotationPresent(PathVariable.class)) {
                String name = p.getAnnotation(PathVariable.class).value();
                args[i] = exchange.getIn().getHeader(name.isEmpty() ? p.getName() : name);
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    /**
     * The rendered template is text. A {@code String} parameter takes it verbatim (the JSON string
     * style); any other type gets it parsed into that type. Without a request template the message
     * body is used, and is serialised to JSON when the parameter is a String.
     */
    private Object convertBody(Object payload, Class<?> targetType) throws Exception {
        if (payload == null || targetType.isInstance(payload)) {
            return payload;
        }
        if (targetType == String.class) {
            return objectMapper.writeValueAsString(payload);
        }
        if (payload instanceof String text) {
            return objectMapper.readValue(text, targetType);
        }
        return objectMapper.convertValue(payload, targetType);
    }

    private MultiValueMap<String, String> buildHeaders(Exchange exchange) {
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        List<String> allowList = endpoint.getForwardHeaders() == null ? null
                : Arrays.stream(endpoint.getForwardHeaders().split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        for (Map.Entry<String, Object> e : exchange.getIn().getHeaders().entrySet()) {
            String name = e.getKey();
            if (allowList != null) {
                if (!allowList.contains(name)) continue;
            } else if (SKIPPED_HEADER_PREFIXES.stream().anyMatch(name::startsWith)) {
                continue;
            }
            if (e.getValue() instanceof String v) {
                headers.add(name, v);
            }
        }
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> buildQueryParams(Exchange exchange) {
        Object raw = exchange.getIn().getHeader(endpoint.getQueryParamsHeader());
        Map<String, String> params = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((k, v) -> params.put(String.valueOf(k), String.valueOf(v)));
        }
        return params;
    }
}
