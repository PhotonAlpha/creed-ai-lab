package com.creed.resource.envmatrix.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Same posture as the sibling resource modules: {@code permitAll} plus CSRF disabled, with the OAuth2
 * resource-server wiring left commented out so the module starts locally without a reachable issuer.
 * Flip both lines together to put it behind the mesh's JWTs.
 *
 * <p>CORS is opt-in via {@code env-matrix.cors.allowed-origins} (set by the {@code dev} profile) for
 * the case where a browser calls this service directly instead of through the Vite proxy. The normal
 * dev setup does not need it — Vite proxies {@code /api}, so the browser sees a single origin.
 *
 * <p>The {@link CorsConfigurationSource} is deliberately built as a local object rather than exposed
 * as a bean: Spring MVC's {@code mvcHandlerMappingIntrospector} is itself a
 * {@code CorsConfigurationSource}, so a second bean of that type makes Spring Security's by-type
 * injection ambiguous and the context fails to start.
 */
@Configuration
@EnableWebSecurity
@Slf4j
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Value("${env-matrix.cors.allowed-origins:}") String allowedOrigins) throws Exception {

        http.authorizeHttpRequests(registry -> registry.anyRequest().permitAll())
//                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .csrf(AbstractHttpConfigurer::disable);

        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (origins.isEmpty()) {
            http.cors(AbstractHttpConfigurer::disable);
        } else {
            log.info("CORS enabled for origins {}", origins);
            http.cors(cors -> cors.configurationSource(corsSource(origins)));
        }
        return http.build();
    }

    private static CorsConfigurationSource corsSource(List<String> origins) {
        CorsConfiguration config = new CorsConfiguration();
        // Patterns rather than plain origins so entries like http://localhost:* work.
        config.setAllowedOriginPatterns(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
