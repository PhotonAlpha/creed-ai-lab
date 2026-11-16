package com.creed.resource.lbhealth.lb;

import com.creed.resource.lbhealth.config.LbHealthConfiguration;
import com.creed.resource.lbhealth.config.LbHealthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerEagerLoadProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalancerProperties;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the health-check settings of {@value LbHealthConfiguration#TARGET_SERVICE_ID} at start-up and logs one
 * WARN per violated rule. It does not fail start-up: every violation degrades the load balancer but does not
 * break it.
 *
 * <p>It reads {@link LoadBalancerClientFactory#getProperties(String)}, the properties the supplier really gets. If
 * the service appears under {@code spring.cloud.loadbalancer.clients.<id>}, that block replaces the global one
 * WHOLESALE (no inheritance, despite the javadoc), so a value set only globally does not apply to it. Checking the
 * effective object catches that too.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HealthCheckConfigValidator implements ApplicationRunner {

    private final LoadBalancerClientFactory loadBalancerClientFactory;
    private final DiscoveryClient discoveryClient;
    private final LoadBalancerEagerLoadProperties eagerLoadProperties;
    private final LbHealthProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        String serviceId = LbHealthConfiguration.TARGET_SERVICE_ID;
        LoadBalancerProperties effective = loadBalancerClientFactory.getProperties(serviceId);
        LoadBalancerProperties.HealthCheck healthCheck = effective == null
                ? new LoadBalancerProperties.HealthCheck() : effective.getHealthCheck();
        int instances = discoveryClient.getInstances(serviceId).size();

        List<String> warnings = validate(healthCheck, instances, properties.healthCheck());
        if (!eagerLoadProperties.getClients().contains(serviceId)) {
            warnings.add("spring.cloud.loadbalancer.eager-load.clients does not list " + serviceId
                    + ": the supplier (and its first health-check round) is created by the first request,"
                    + " which then waits for that whole round");
        }
        warnings.forEach(warning -> log.warn("[LB-HEALTH-CONFIG] {}: {}", serviceId, warning));
        if (warnings.isEmpty()) {
            log.info("[LB-HEALTH-CONFIG] {}: OK - {} instances, interval={}, refetch-instances-interval={},"
                    + " worst-case round={}", serviceId, instances, healthCheck.getInterval(),
                    healthCheck.getRefetchInstancesInterval(),
                    worstCaseRound(instances, properties.healthCheck(), healthCheck.getInterval()));
        }
    }

    /**
     * @param healthCheck the effective Spring Cloud health-check properties of the service
     * @param instances   how many instances discovery currently returns
     * @param probe       the probe client's mode and timeouts
     * @return one message per violated rule, empty when the configuration is sound
     */
    public static List<String> validate(LoadBalancerProperties.HealthCheck healthCheck, int instances,
            LbHealthProperties.HealthCheck probe) {
        List<String> warnings = new ArrayList<>();
        Duration interval = healthCheck.getInterval();
        Duration refetch = healthCheck.getRefetchInstancesInterval();

        if (healthCheck.isUpdateResultsList()) {
            warnings.add("health-check.update-results-list=true: every round re-emits a growing list (1, 2, 3 ...)"
                    + " and get() returns that partial list until the round ends - set it to false");
        }
        if (!healthCheck.getRefetchInstances()) {
            warnings.add("health-check.refetch-instances=false: the blocking DiscoveryClient is read once, so"
                    + " instances added or removed later are never seen");
            return warnings; // the two refetch rules below only apply when refetching
        }
        if (refetch.compareTo(interval) <= 0) {
            warnings.add("health-check.refetch-instances-interval (" + refetch + ") <= interval (" + interval
                    + "): every refetch cancels the running health-check stream through switchMap, including its"
                    + " interval timer, so the interval never fires and checks only run once per refetch");
        }
        Duration round = worstCaseRound(instances, probe, interval);
        if (round.compareTo(refetch) >= 0) {
            warnings.add("worst-case round " + round + " (" + describeRound(instances, probe, interval)
                    + ") >= refetch-instances-interval (" + refetch + "): a slow round is cancelled by the next"
                    + " refetch before it finishes, and get() stays on the last completed list forever");
        }
        if (!healthCheck.getInitialDelay().isZero()) {
            warnings.add("health-check.initial-delay=" + healthCheck.getInitialDelay() + ": get() has no list"
                    + " until initial-delay plus one round has passed; requests wait that long");
        }
        return warnings;
    }

    /**
     * Blocking probes run one after the other on a single thread and Spring's per-probe {@code timeout(interval)}
     * cannot interrupt them, so a round lasts {@code instances × probeTimeout}. WebClient probes run concurrently and
     * that timeout does apply, so a round lasts at most {@code min(probeTimeout, interval)}.
     */
    static Duration worstCaseRound(int instances, LbHealthProperties.HealthCheck probe, Duration interval) {
        return switch (probe.mode()) {
            case BLOCKING -> probe.probeTimeout().multipliedBy(instances);
            case WEBCLIENT -> probe.probeTimeout().compareTo(interval) < 0 ? probe.probeTimeout() : interval;
        };
    }

    private static String describeRound(int instances, LbHealthProperties.HealthCheck probe, Duration interval) {
        return switch (probe.mode()) {
            case BLOCKING -> instances + " instances × " + probe.probeTimeout() + " serial blocking probes";
            case WEBCLIENT -> "concurrent probes, min(probe timeout " + probe.probeTimeout() + ", interval "
                    + interval + ")";
        };
    }
}
