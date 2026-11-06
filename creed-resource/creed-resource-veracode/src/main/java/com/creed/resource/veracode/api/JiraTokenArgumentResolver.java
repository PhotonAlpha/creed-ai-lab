package com.creed.resource.veracode.api;

import com.creed.resource.veracode.service.ConfluenceToken;
import com.creed.resource.veracode.service.JiraToken;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * A {@link JiraToken} controller parameter is built from the page's headers: {@value JiraToken#HEADER} and, for Jira
 * Cloud, {@value JiraToken#USER_HEADER}; the page's issue type from {@value JiraToken#ISSUE_TYPE_HEADER}. Missing headers give an empty token - the service refuses it
 * ({@code jira_token_required}), not the binding. A {@link ConfluenceToken} likewise, from
 * {@value ConfluenceToken#HEADER} and {@value ConfluenceToken#USER_HEADER}.
 */
@Configuration(proxyBeanMethods = false)
public class JiraTokenArgumentResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return JiraToken.class.equals(parameter.getParameterType())
                || ConfluenceToken.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        if (ConfluenceToken.class.equals(parameter.getParameterType())) {
            return new ConfluenceToken(webRequest.getHeader(ConfluenceToken.HEADER),
                    webRequest.getHeader(ConfluenceToken.USER_HEADER));
        }
        return new JiraToken(webRequest.getHeader(JiraToken.HEADER), webRequest.getHeader(JiraToken.USER_HEADER),
                webRequest.getHeader(JiraToken.ISSUE_TYPE_HEADER));
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }
}
