package com.creed.parent.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.creed.parent.repo.AccessConfiguration;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.encrypt.RsaSecretEncryptor;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.text.ParseException;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 7/23/25
 */

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Value("${access.arti:ccrg}")
    private String keyId;
    @Value("${access.arti.token-key}")
    private String tokenKey;
    @Value("${access.arti.token-value}")
    private String tokenValue;
    @Value("${access.confluence.token}")
    private String confluenceTokenValue;

    @Bean
    TextEncryptor textEncryptor() throws IOException, ParseException, JOSEException {
        JWKSet jwkSet = JWKSet.parse(IOUtils.toString(new ClassPathResource("vault/vault.json").getInputStream(), StandardCharsets.UTF_8));
        JWK jwk = jwkSet.getKeyByKeyId(keyId);
        RSAKey rsaKey = jwk.toRSAKey();
        return new RsaSecretEncryptor(new KeyPair(rsaKey.toPublicKey(), rsaKey.toPrivateKey()));
    }
//    @Bean
    AccessConfiguration accessConfiguration(TextEncryptor textEncryptor) {
        AccessConfiguration accessConfiguration = new AccessConfiguration();
        accessConfiguration.setTokenKey(textEncryptor.decrypt(tokenKey));
        accessConfiguration.setTokenValue(textEncryptor.decrypt(tokenValue));
        accessConfiguration.setBearerToken(textEncryptor.decrypt(confluenceTokenValue));
        return accessConfiguration;
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

}
