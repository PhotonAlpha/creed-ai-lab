package com.creed.simple.tutorial.component.httpexchange;

import org.apache.camel.Category;
import org.apache.camel.Consumer;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.spi.UriEndpoint;
import org.apache.camel.spi.UriParam;
import org.apache.camel.spi.UriPath;
import org.apache.camel.support.DefaultEndpoint;

/**
 * Endpoint of the {@code httpexchange:} component. Producer only - an
 * {@code @HttpExchange} interface is something you call, never something that calls you.
 */
@UriEndpoint(firstVersion = "1.0.0", scheme = "httpexchange", title = "HTTP Exchange",
             syntax = "httpexchange:beanName/methodName", producerOnly = true,
             category = { Category.HTTP })
public class HttpExchangeEndpoint extends DefaultEndpoint {

    @UriPath(description = "Name of the Spring bean holding the @HttpExchange proxy")
    private final String beanName;

    @UriPath(description = "Name of the interface method to invoke")
    private final String methodName;

    @UriParam(description = "Classpath Freemarker template rendered BEFORE the call; its output "
                          + "is bound to the @RequestBody parameter")
    private String requestTemplate;

    @UriParam(description = "Classpath Freemarker template rendered AFTER the call; its output "
                          + "becomes the message body")
    private String responseTemplate;

    @UriParam(description = "Fully qualified type the rendered response is unmarshalled into. "
                          + "Leave unset to keep the rendered text as-is.")
    private String unmarshalTo;

    @UriParam(defaultValue = "true",
              description = "Convert the returned object into plain Maps/Lists before rendering "
                          + "the response template, so templates never depend on bean accessors")
    private boolean normalizeResponse = true;

    @UriParam(description = "Comma-separated Exchange headers forwarded as HTTP headers. "
                          + "Unset forwards every String-valued header that is not Camel-internal.")
    private String forwardHeaders;

    @UriParam(defaultValue = "HttpExchangeQueryParams",
              description = "Exchange header holding a Map used for the @RequestParam Map parameter")
    private String queryParamsHeader = "HttpExchangeQueryParams";

    public HttpExchangeEndpoint(String uri, HttpExchangeComponent component,
                                String beanName, String methodName) {
        super(uri, component);
        this.beanName = beanName;
        this.methodName = methodName;
    }

    @Override
    public Producer createProducer() {
        return new HttpExchangeProducer(this);
    }

    @Override
    public Consumer createConsumer(Processor processor) {
        throw new UnsupportedOperationException(
                "httpexchange: is producer-only; you cannot consume from an @HttpExchange interface");
    }

    public String getBeanName() { return beanName; }
    public String getMethodName() { return methodName; }

    public String getRequestTemplate() { return requestTemplate; }
    public void setRequestTemplate(String requestTemplate) { this.requestTemplate = requestTemplate; }

    public String getResponseTemplate() { return responseTemplate; }
    public void setResponseTemplate(String responseTemplate) { this.responseTemplate = responseTemplate; }

    public String getUnmarshalTo() { return unmarshalTo; }
    public void setUnmarshalTo(String unmarshalTo) { this.unmarshalTo = unmarshalTo; }

    public boolean isNormalizeResponse() { return normalizeResponse; }
    public void setNormalizeResponse(boolean normalizeResponse) { this.normalizeResponse = normalizeResponse; }

    public String getForwardHeaders() { return forwardHeaders; }
    public void setForwardHeaders(String forwardHeaders) { this.forwardHeaders = forwardHeaders; }

    public String getQueryParamsHeader() { return queryParamsHeader; }
    public void setQueryParamsHeader(String queryParamsHeader) { this.queryParamsHeader = queryParamsHeader; }
}
