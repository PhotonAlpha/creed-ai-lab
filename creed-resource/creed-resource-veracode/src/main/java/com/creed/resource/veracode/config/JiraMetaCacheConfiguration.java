package com.creed.resource.veracode.config;

import com.creed.resource.veracode.service.JiraLookupService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * The create-screen cache {@link JiraLookupService} reads analyses through ({@code veracode.jira.meta-cache-ttl}):
 * issue types + fields per token fingerprint, project and issue type - never issues. Caffeine rather than a map: two
 * analyses missing at once (StrictMode, a double click) read Jira once, and expiry needs no sweep.
 */
@Configuration
public class JiraMetaCacheConfiguration {

    @Bean
    Cache<String, JiraLookupService.CreateMeta> jiraMetaCache(JiraProperties props) {
        return metaCache(props.metaCacheTtl());
    }

    /**
     * Also what tests build the service with. A TTL of 0 (or less) keeps nothing - every entry expires as it is
     * written, so each analysis reads Jira - while concurrent misses still share one read.
     */
    public static Cache<String, JiraLookupService.CreateMeta> metaCache(Duration ttl) {
        boolean on = ttl.isPositive();
        return Caffeine.newBuilder()
                .expireAfterWrite(on ? ttl : Duration.ZERO)
                .maximumSize(on ? 500 : 0)
                .build();
    }
}
