package com.creed.gatewayproxy.config;

import java.time.Clock;
import java.time.ZoneId;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.creed.gatewayproxy.service.splunk.BlockWindows;
import com.creed.gatewayproxy.service.splunk.ClientHostResolver;
import com.creed.gatewayproxy.service.splunk.SplunkAdmin;
import com.creed.gatewayproxy.service.splunk.SplunkBroker;
import com.creed.gatewayproxy.service.splunk.SplunkLoginClient;
import com.creed.gatewayproxy.service.splunk.SplunkSecrets;
import com.creed.gatewayproxy.service.splunk.Totp;
import com.creed.gatewayproxy.service.splunk.audit.JdbcAuditStore;
import com.creed.gatewayproxy.service.splunk.audit.MemoryAuditStore;
import com.creed.gatewayproxy.service.splunk.audit.SplunkAuditStore;

/**
 * Wires the Splunk session broker. The secrets are resolved (and, on a terminal, prompted for)
 * while the context starts — before the server listens, so nothing is served half-configured.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class SplunkConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock splunkClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    SplunkSecrets.Prompt splunkSecretPrompt(@Value("${creed.secrets.prompt:true}") boolean prompt) {
        SplunkSecrets.Prompt terminal = SplunkSecrets.terminalPrompt(prompt);
        return terminal != null ? terminal : question -> null;
    }

    @Bean
    SplunkSecrets splunkSecrets(Environment env, SplunkProperties splunk, SplunkSecrets.Prompt prompt) {
        SplunkSecrets secrets = new SplunkSecrets(env, splunk, prompt);
        if (secrets.totpGenerated()) {
            log.warn("ENV_MATRIX_TOTP_SECRET not supplied: using a random secret for this run — codes are only "
                    + "meaningful to this process and its page, never to an authenticator app");
        }
        return secrets;
    }

    @Bean
    Totp totp(TotpProperties properties, SplunkSecrets secrets, Clock clock) {
        return new Totp(properties, secrets.totpSecret(), clock::millis);
    }

    @Bean
    SplunkLoginClient splunkLoginClient(SplunkProperties splunk) {
        if (splunk.enabled() && splunk.tlsInsecure()) {
            log.warn("creed.splunk.tls-insecure: Splunk's certificate is not verified ({})",
                    splunk.targets().stream().map(SplunkProperties.Target::loginUrl).toList());
        }
        return new SplunkLoginClient(splunk);
    }

    /** pg / mysql: connects and creates the table now — the broker refuses to run without its audit. */
    @Bean(destroyMethod = "close")
    SplunkAuditStore splunkAuditStore(SplunkProperties splunk, SplunkSecrets secrets) {
        SplunkAuditStore store = "memory".equals(splunk.audit().store())
                ? new MemoryAuditStore()
                : new JdbcAuditStore(splunk.audit(), secrets.dbPassword());
        if (store instanceof MemoryAuditStore) {
            log.warn("creed.splunk.audit.store=memory: the audit keeps the newest 500 rows and is lost on restart");
        }
        store.init();
        return store;
    }

    @Bean
    BlockWindows splunkBlockWindows(SplunkProperties splunk) {
        return new BlockWindows(splunk.block().windows(), ZoneId.of(splunk.block().zone()));
    }

    @Bean(destroyMethod = "close")
    ClientHostResolver clientHostResolver(SplunkProperties splunk, Clock clock) {
        return new ClientHostResolver(splunk.clientHost(), clock);
    }

    @Bean
    SplunkBroker splunkBroker(Totp totp, SplunkLoginClient loginClient, SplunkProperties splunk, SplunkSecrets secrets,
                              SplunkAuditStore store, BlockWindows block, Clock clock) {
        SplunkBroker broker = new SplunkBroker(totp, loginClient, splunk, secrets, store, block, clock);
        log.info("Splunk broker: mode={}, targets={}, default={}, audit={}, totp period={}s",
                loginClient.mode(), splunk.targets().stream().map(t -> t.id() + (secrets.password(t) != null ? "" : "(no password)")
                        + " block=" + describe(broker.blockOf(t)) + (t.block() == null ? "" : " (own)")
                        + (t.allow() == null ? "" : " allow=" + t.allow().windows())).toList(),
                splunk.defaultTarget(), splunk.audit().store(), totp.config().periodSeconds());
        return broker;
    }

    @Bean
    SplunkAdmin splunkAdmin(SplunkBroker broker, SplunkProperties splunk, SplunkSecrets secrets, Clock clock) {
        SplunkAdmin admin = new SplunkAdmin(broker, splunk, secrets.adminToken(), clock);
        log.info("Splunk admin API (/api/env-matrix/splunk/admin/**): {}", admin.enabled() ? "on"
                : "off — " + SplunkSecrets.ADMIN_TOKEN_VARIABLE + " not supplied");
        return admin;
    }

    private static String describe(BlockWindows block) {
        return (block.windows().isEmpty() ? "none" : block.toString()) + " " + block.zone();
    }
}
