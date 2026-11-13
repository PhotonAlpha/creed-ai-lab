package com.creed.resource.veracode.config;

import com.creed.resource.veracode.service.JiraLookupService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * The create-screen cache {@link JiraLookupService} reads analyses through ({@code veracode.jira.meta-cache-ttl}):
 * Jira's createmeta answers as read - a project's issue types, an issue type's fields - per token fingerprint, never
 * issues. Caffeine rather than a map: misses at once (the SCA and SAST halves of one analysis, StrictMode, a double
 * click) read Jira once, and expiry needs no sweep.
 */
@Configuration
public class JiraMetaCacheConfiguration {

    @Bean
    Cache<String, List<?>> jiraMetaCache(JiraProperties props) {
        return metaCache(props.metaCacheTtl());
    }

    /**
     * Also what tests build the service with. A TTL of 0 (or less) keeps nothing - every entry expires as it is
     * written, so each analysis reads Jira - while concurrent misses still share one read.
     */
    public static Cache<String, List<?>> metaCache(Duration ttl) {
        boolean on = ttl.isPositive();
        return Caffeine.newBuilder()
                .expireAfterWrite(on ? ttl : Duration.ZERO)
                .maximumSize(on ? 500 : 0)
                .build();
    }
}
