package com.creed.resource.catalog.config;

import java.time.Duration;

import com.github.ben.manes.caffeine.cache.Caffeine;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
public class CachedJwtDecoderConfiguration {

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${creed.oauth2.jwk-set-cache-minutes:10}") int jwkSetCacheMinutes) {
        Duration jwkSetCacheTtl = Duration.ofMinutes(jwkSetCacheMinutes);
        org.springframework.cache.Cache jwkSetCache = new CaffeineCache(
                "creed-jwk-set",
                Caffeine.newBuilder().maximumSize(32).expireAfterWrite(jwkSetCacheTtl).build());
        return NimbusJwtDecoder.withIssuerLocation(issuerUri).cache(jwkSetCache).build();
    }
}
