package com.creed.simple.tutorial.component.httpexchange;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import org.apache.camel.Exchange;

import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * Renders a classpath Freemarker template against an {@link Exchange}.
 *
 * <p>The model deliberately uses the same variable names as the camel-freemarker component
 * ({@code body}, {@code headers}, {@code exchange}), so one and the same {@code .ftl} file works
 * whether it is rendered by a {@code freemarker:} endpoint or by this component.
 *
 * <p>This is what lets the component drop the ProducerTemplate entirely: rendering needs
 * FreeMarker, not a Camel endpoint round-trip.
 */
final class FreemarkerRenderer {

    private final Configuration configuration;

    FreemarkerRenderer() {
        this.configuration = new Configuration(Configuration.VERSION_2_3_34);
        this.configuration.setClassLoaderForTemplateLoading(
                FreemarkerRenderer.class.getClassLoader(), "/");
        this.configuration.setDefaultEncoding("UTF-8");
        this.configuration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        this.configuration.setLogTemplateExceptions(false);
        this.configuration.setWrapUncheckedExceptions(true);
    }

    /**
     * @param templatePath classpath path, e.g. {@code tutorial/templates/artisan-request.ftl}
     * @param body         the value exposed to the template as {@code body}
     */
    String render(String templatePath, Object body, Exchange exchange) throws Exception {
        Template template = configuration.getTemplate(templatePath);
        Map<String, Object> model = new HashMap<>();
        model.put("body", body);
        model.put("headers", exchange.getIn().getHeaders());
        model.put("exchange", exchange);
        StringWriter out = new StringWriter();
        template.process(model, out);
        return out.toString();
    }
}
