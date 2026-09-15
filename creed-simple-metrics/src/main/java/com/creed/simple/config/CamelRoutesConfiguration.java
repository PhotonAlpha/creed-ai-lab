package com.creed.simple.config;

/**
 * Loads {@code camel-context.xml} — the classic {@code <camelContext>} Spring XML DSL holding every
 * route, the REST DSL surface and the {@code <restConfiguration>}.
 *
 * <p><strong>Why this is not an {@code @ImportResource} on the application class</strong> (where it
 * used to live): a condition on the application class is not an option, and the routes must go away
 * together with {@link CamelConfig}. They reference the {@code https://<service-id>} endpoints that
 * CamelConfig's {@code HttpComponent} serves, so routes without that component fail the context at
 * startup with an unresolvable-endpoint error rather than falling back to anything.
 *
 * <p>The same pair of conditions as {@link CamelConfig}, so the two can only be on or off together.
 * Note the third thing that must follow the switch, and cannot be expressed here:
 * {@code camel.servlet.mapping.enabled} — the camel-servlet auto-configuration maps
 * {@code CamelHttpTransportServlet} at {@code /camel/*} whenever the jar is present, and a servlet
 * mapping beats the {@code /} DispatcherServlet, so a leftover Camel servlet would swallow every
 * request meant for the REST controllers. {@code application.yml} drives both from one
 * {@code CREED_CAMEL_ENABLED} variable.
 */
//@Configuration(proxyBeanMethods = false)
//@ConditionalOnClass(CamelContext.class)
//@ConditionalOnProperty(prefix = "creed.camel", name = "enabled", havingValue = "true", matchIfMissing = true)
//@ImportResource("classpath:camel-context.xml")
public class CamelRoutesConfiguration {
}
