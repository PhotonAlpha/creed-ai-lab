package com.creed.simple.config;

import com.creed.simple.lb.HttpPoolProperties;
import com.creed.simple.lb.LoadBalancerRoutePlanner;
import io.micrometer.observation.ObservationRegistry;
import org.apache.camel.Endpoint;
import org.apache.camel.component.http.HttpComponent;
import org.apache.hc.client5.http.impl.ChainElement;
import org.apache.hc.client5.http.impl.DefaultConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.observation.ObservingOptions;
import org.apache.hc.client5.http.observation.impl.ObservationClassicExecInterceptor;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.Environment;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.httpclient5.LogbookHttpExecHandler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The one factory every camel-http component of this module is built by - the default {@code http} / {@code https}
 * and each named one under {@code creed.camel.http-components.<name>} - so they all carry the same route planner,
 * keep-alive strategy and exec chain (Logbook outermost, observation inside RETRY, lbAudit innermost) and differ
 * only in their pool and timeouts.
 */
public final class CamelHttpComponents {

    /** Where named components are declared: {@code creed.camel.http-components.<name>.*}, unset keys from {@link #DEFAULTS}. */
    public static final String NAMED = "creed.camel.http-components";
    /** The default pool's keys, and the fallback of every named component. */
    public static final String DEFAULTS = "creed.camel.http";

    /**
     * A component name is a URI scheme ({@code catalog-bulk:https://...}): letters, digits, {@code + - .}, starting
     * with a letter. Lower case only - Spring bean names are case-sensitive, Camel's scheme lookup is not.
     */
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9+.-]*");

    private CamelHttpComponents() {
    }

    /** Built per call: see {@code httpComponent} in {@link CamelConfig} for the default, the registrar for named ones. */
    public record Shared(LoadBalancerRoutePlanner routePlanner, ObservationRegistry observationRegistry,
                         Logbook logbook, int poolStatsEvery, Duration poolStatsSlowThreshold) {
    }

    /**
     * The default component ({@code http} / {@code https}): {@code https://<service-id>/...} as before.
     *
     * @param pool closed by {@code HttpComponent.doStop()}; the pool bean declares {@code destroyMethod=""}
     */
    public static HttpComponent create(PoolingHttpClientConnectionManager pool, HttpPoolProperties props, Shared shared) {
        return configure(new HttpComponent(), pool, props, shared);
    }

    /**
     * A named component: its endpoints must spell the protocol ({@code catalog-bulk:https://catalog-resource/...}).
     * camel-http decides TLS by {@code uri.startsWith("https")} ({@code HttpHelper.isSecureConnection}), so
     * {@code catalog-bulk://catalog-resource/...} would silently go out as plain HTTP to an mTLS server - refused here
     * at endpoint creation instead.
     */
    public static HttpComponent createNamed(String name, PoolingHttpClientConnectionManager pool,
                                            HttpPoolProperties props, Shared shared) {
        return configure(new ExplicitProtocolHttpComponent(name), pool, props, shared);
    }

    private static HttpComponent configure(HttpComponent component, PoolingHttpClientConnectionManager pool,
                                           HttpPoolProperties props, Shared shared) {
        // component-level, so HttpEndpoint marks it connectionManagerShared and only the component's doStop()
        // closes it - an endpoint stopping (route removed, toD cache eviction) leaves it open for the others
        component.setClientConnectionManager(pool);
        // HttpEndpoint applies the configurer after its own builder setup, so the planner is not overridden
        component.setHttpClientConfigurer(builder -> builder
                .setRoutePlanner(shared.routePlanner())
                // HttpClient's stock strategy: header-less Tomcat responses get RequestConfig's 3-minute
                // keep-alive, so idle connections are bounded by the pool evictor's max-idle-time
                // (closeIdle), not by keep-alive
                .setKeepAliveStrategy(DefaultConnectionKeepAliveStrategy.INSTANCE)
                // Zalando Logbook: the FULL request/response audit (headers/cookies/bodies with
                // obfuscation, `logbook.*` config). First per the Logbook docs, so it sees the
                // message before other interceptors touch it.
                .addExecInterceptorFirst("logbook", new LogbookHttpExecHandler(shared.logbook()))
                // Apache's hc5 observation support: an `http.client.request` Observation (timer +
                // trace propagation) per attempt — the camel-http twin of the RestClient's
                // `http.client.requests`. Still placed right inside RETRY, so it times each
                // ATTEMPT, not the whole retried operation, and so it brackets the same work the
                // lbAudit handler below logs.
                //
                // Deliberately constructed directly rather than via
                // HttpClientObservationSupport.enable(builder, ...): that helper installs every
                // interceptor with addExecInterceptorFirst, which would (a) move this outside RETRY
                // and silently change it from per-attempt to per-operation timing, and (b) also bind
                // its own ConnPoolMeters, duplicating the pool gauges ConnectionPoolMetrics already
                // publishes under the names the dashboards use.
                //
                // Name change from the retired Micrometer handler: the Observation (and therefore
                // the derived timer and span) is `http.client.request`, was
                // `httpcomponents.httpclient.request`. Nothing in monitoring/ queried the old name.
                .addExecInterceptorAfter(ChainElement.RETRY.name(), "micrometer",
                        new ObservationClassicExecInterceptor(shared.observationRegistry(), ObservingOptions.DEFAULT))
                // innermost (inside retry): logs the instance the route planner picked, per attempt,
                // plus route/total occupancy of THIS component's pool (sampled: creed.audit.pool-stats.*)
                .addExecInterceptorLast("lbAudit", new PoolingHttpClientConnectionManagerAuditExecHandler(
                        pool, shared.poolStatsEvery(), shared.poolStatsSlowThreshold())));
        component.setConnectionRequestTimeout(props.connectionRequestTimeout().toMillis());
        component.setResponseTimeout(props.responseTimeout().toMillis());
        return component;
    }

    /** {@code creed.camel.http.*}, with {@link HttpPoolProperties}' defaults for anything unset. */
    public static HttpPoolProperties defaults(Environment environment) {
        return Binder.get(environment).bindOrCreate(DEFAULTS, HttpPoolProperties.class);
    }

    /**
     * {@code creed.camel.http-components.<name>.*}, each unset key taken from {@code creed.camel.http.*} (then the
     * record's defaults): a named pool states only what differs from the default one.
     */
    public static HttpPoolProperties named(Environment environment, String name) {
        List<ConfigurationPropertySource> sources = new ArrayList<>();
        Iterable<ConfigurationPropertySource> all = ConfigurationPropertySources.get(environment);
        // first source holding a key wins: the named block, then the default block
        all.forEach(s -> sources.add(s.withPrefix(NAMED + "." + name)));
        all.forEach(s -> sources.add(s.withPrefix(DEFAULTS)));
        return new Binder(sources).bindOrCreate(ConfigurationPropertyName.EMPTY,
                Bindable.of(HttpPoolProperties.class), BindHandler.DEFAULT);
    }

    /** The names declared under {@code creed.camel.http-components}, validated as URI schemes. */
    public static List<String> names(Environment environment) {
        Map<String, Object> declared = Binder.get(environment)
                .bind(NAMED, Bindable.mapOf(String.class, Object.class))
                .orElse(Map.of());
        declared.keySet().forEach(CamelHttpComponents::requireValidName);
        return List.copyOf(declared.keySet());
    }

    static void requireValidName(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalStateException(NAMED + "." + name + ": a component name is a URI scheme - lower-case"
                    + " letters, digits, '+', '-', '.', starting with a letter (e.g. catalog-bulk)");
        }
        if (name.equals("http") || name.equals("https")) {
            throw new IllegalStateException(NAMED + "." + name + ": 'http' / 'https' is the default component"
                    + " (creed.camel.http.*), pick another name");
        }
    }

    /** Refuses {@code name://host/...}: the endpoint must say {@code name:https://...} or {@code name:http://...}. */
    static final class ExplicitProtocolHttpComponent extends HttpComponent {

        private final String name;

        ExplicitProtocolHttpComponent(String name) {
            this.name = name;
        }

        @Override
        protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
            if (!remaining.startsWith("https:") && !remaining.startsWith("http:")) {
                throw new IllegalArgumentException("camel-http component '" + name + "' needs the protocol in the"
                        + " endpoint URI - " + name + ":https://<service-id>/... (or " + name + ":http://...); '"
                        + uri + "' would be sent as plain HTTP");
            }
            return super.createEndpoint(uri, remaining, parameters);
        }
    }
}
