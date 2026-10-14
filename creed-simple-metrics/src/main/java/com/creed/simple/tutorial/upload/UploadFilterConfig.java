package com.creed.simple.tutorial.upload;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Mounts {@link Base64FileMultipartFilter} on the upload endpoints only.
 *
 * <p>Registered through a {@link FilterRegistrationBean} rather than {@code @Component} so the
 * URL pattern is explicit: a {@code @Component} filter would be mapped to {@code /*} and rewrite
 * every multipart request of the application that happens to have a "file" field.
 */
@Configuration
public class UploadFilterConfig {

    @Bean
    public FilterRegistrationBean<Base64FileMultipartFilter> base64FileMultipartFilter() {
        FilterRegistrationBean<Base64FileMultipartFilter> registration =
                new FilterRegistrationBean<>(new Base64FileMultipartFilter());
        registration.addUrlPatterns("/camel/api/v1/files/*");
        registration.setName("base64FileMultipartFilter");
        return registration;
    }
}
