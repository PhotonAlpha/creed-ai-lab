package com.creed.gateway.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

//@Configuration
public class CachedReactiveJwtDecoderConfiguration {

    @Bean
    ReactiveJwtDecoder reactiveJwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${creed.oauth2.jwk-set-cache-minutes:10}") int jwkSetCacheMinutes) {
        Duration jwkSetCacheTtl = Duration.ofMinutes(jwkSetCacheMinutes);
        org.springframework.cache.Cache jwkSetCache = new CaffeineCache(
                "creed-jwk-set",
                Caffeine.newBuilder().maximumSize(32).expireAfterWrite(jwkSetCacheTtl).build());
        JwtDecoder syncDecoder = NimbusJwtDecoder.withIssuerLocation(issuerUri)
                .cache(jwkSetCache)
                .build();
        return token -> Mono.fromCallable(() -> syncDecoder.decode(token))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
