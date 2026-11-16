package com.creed.resource.lbhealth.lb;

import com.creed.resource.lbhealth.config.LbHealthProperties;
import com.creed.resource.lbhealth.config.LbHealthProperties.Mode;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.loadbalancer.LoadBalancerProperties;

import java.time.Duration;
import java.util.List;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;

class HealthCheckConfigValidatorTest {

    private static final LbHealthProperties.HealthCheck BLOCKING_2S_3S =
            new LbHealthProperties.HealthCheck(Mode.BLOCKING, ofSeconds(2), ofSeconds(3));

    @Test
    void theRecommendedSettingsPass() {
        assertThat(HealthCheckConfigValidator.validate(recommended(), 10, BLOCKING_2S_3S)).isEmpty();
    }

    @Test
    void springsDefaultsAreFlagged() {
        // interval 25s, refetch off, update-results-list true
        List<String> warnings = HealthCheckConfigValidator.validate(new LoadBalancerProperties.HealthCheck(), 10,
                BLOCKING_2S_3S);

        assertThat(warnings).hasSize(2)
                .anyMatch(w -> w.startsWith("health-check.update-results-list=true"))
                .anyMatch(w -> w.startsWith("health-check.refetch-instances=false"));
    }

    @Test
    void refetchNotLongerThanIntervalIsFlagged() {
        LoadBalancerProperties.HealthCheck healthCheck = recommended();
        healthCheck.setRefetchInstancesInterval(ofSeconds(30));

        assertThat(HealthCheckConfigValidator.validate(healthCheck, 1, BLOCKING_2S_3S))
                .singleElement().asString().contains("<= interval");
    }

    @Test
    void serialBlockingRoundsAreSizedByInstancesTimesTimeout() {
        LoadBalancerProperties.HealthCheck healthCheck = recommended();
        healthCheck.setRefetchInstancesInterval(ofSeconds(50));

        // 10 × 5s = 50s >= 50s
        assertThat(HealthCheckConfigValidator.validate(healthCheck, 10, BLOCKING_2S_3S))
                .singleElement().asString().contains("worst-case round PT50S");
        // 9 × 5s = 45s < 50s
        assertThat(HealthCheckConfigValidator.validate(healthCheck, 9, BLOCKING_2S_3S)).isEmpty();
    }

    @Test
    void concurrentWebClientRoundsAreSizedByOneProbe() {
        LoadBalancerProperties.HealthCheck healthCheck = recommended();
        healthCheck.setRefetchInstancesInterval(ofSeconds(50));
        LbHealthProperties.HealthCheck webClient =
                new LbHealthProperties.HealthCheck(Mode.WEBCLIENT, ofSeconds(2), ofSeconds(3));

        assertThat(HealthCheckConfigValidator.validate(healthCheck, 100, webClient)).isEmpty();
    }

    @Test
    void aNonZeroInitialDelayIsFlagged() {
        LoadBalancerProperties.HealthCheck healthCheck = recommended();
        healthCheck.setInitialDelay(Duration.ofSeconds(10));

        assertThat(HealthCheckConfigValidator.validate(healthCheck, 10, BLOCKING_2S_3S))
                .singleElement().asString().startsWith("health-check.initial-delay=PT10S");
    }

    /** What application.yml ships. */
    private static LoadBalancerProperties.HealthCheck recommended() {
        LoadBalancerProperties.HealthCheck healthCheck = new LoadBalancerProperties.HealthCheck();
        healthCheck.setUpdateResultsList(false);
        healthCheck.setInitialDelay(Duration.ZERO);
        healthCheck.setInterval(ofSeconds(30));
        healthCheck.setRefetchInstances(true);
        healthCheck.setRefetchInstancesInterval(ofSeconds(120));
        return healthCheck;
    }
}
