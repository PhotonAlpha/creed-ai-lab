package com.creed.parent.config;

import freemarker.cache.MruCacheStorage;
import freemarker.template.TemplateExceptionHandler;
import jakarta.servlet.Filter;
import org.springframework.boot.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.ui.freemarker.SpringTemplateLoader;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;

@Configuration
public class ApplicationConfiguration {
    @Bean
    TomcatConnectorCustomizer disabledFacadeDiscard() {
        return (binding) -> binding.setDiscardFacades(false);
    }



    @Bean @Primary
    freemarker.template.Configuration freemarkerConfiguration(ResourceLoader resourceLoader) {
        freemarker.template.Configuration cfg = new freemarker.template.Configuration(freemarker.template.Configuration.getVersion());
        cfg.setTemplateLoader(new SpringTemplateLoader(resourceLoader, "classpath:templates"));
        cfg.setDefaultEncoding("UTF-8");
        cfg.setCacheStorage(new MruCacheStorage(50, 500));
        cfg.setTemplateUpdateDelayMilliseconds(9999999L * 1000L);
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setNumberFormat("0.######");
        return cfg;
    }


    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterBean() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.addAllowedOriginPattern(CorsConfiguration.ALL);
        config.addAllowedHeader(CorsConfiguration.ALL);
        config.addAllowedMethod(CorsConfiguration.ALL);
        config.setExposedHeaders(Arrays.asList(HttpHeaders.CONTENT_DISPOSITION, HttpHeaders.CONTENT_TYPE));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return createFilterBean(new CorsFilter(source), Integer.MIN_VALUE);
    }

    private static <T extends Filter> FilterRegistrationBean<T> createFilterBean(T filter, Integer order) {
        FilterRegistrationBean<T> bean = new FilterRegistrationBean<>(filter);
        bean.setOrder(order);
        return bean;
    }
}
