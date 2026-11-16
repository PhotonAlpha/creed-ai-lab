package com.creed.resource.lbhealth;

import com.creed.resource.lbhealth.api.LbHealthController;
import com.creed.resource.lbhealth.config.LbHealthConfiguration;
import com.creed.resource.lbhealth.lb.ObservableHealthCheckServiceInstanceListSupplier;
import com.creed.resource.lbhealth.upstream.TargetInstanceStates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The whole app on loopback: the 10 target instances are this app's own extra connectors on free ports, probed
 * for real over HTTP by the blocking RestTemplate. Timings are shrunk (interval 1 s, refetch 10 s, probe timeout
 * 300 + 500 ms → worst-case round 8 s) but keep the shipped ratios.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LbHealthApplicationTest {

    private static final String SERVICE_ID = LbHealthConfiguration.TARGET_SERVICE_ID;
    /** Sorted, like {@link #ports}, so lists compare by content. */
    private static final List<Integer> PORTS = IntStream.range(0, 10).mapToObj(i -> freePort()).sorted().toList();

    @DynamicPropertySource
    static void targets(DynamicPropertyRegistry registry) {
        String prefix = "spring.cloud.discovery.client.simple.instances." + SERVICE_ID;
        for (int i = 0; i < PORTS.size(); i++) {
            int port = PORTS.get(i);
            String instanceId = "i%02d".formatted(i);
            registry.add(prefix + "[" + i + "].instance-id", () -> instanceId);
            registry.add(prefix + "[" + i + "].uri", () -> "http://localhost:" + port);
        }
        registry.add("spring.cloud.loadbalancer.health-check.interval", () -> "1s");
        registry.add("spring.cloud.loadbalancer.health-check.refetch-instances-interval", () -> "10s");
        registry.add("creed.lb-health.health-check.connect-timeout", () -> "300ms");
        registry.add("creed.lb-health.health-check.read-timeout", () -> "500ms");
        registry.add("creed.lb-health.debug.enabled", () -> "true");
    }

    @Autowired
    LoadBalancerClientFactory loadBalancerClientFactory;
    @Autowired
    TargetInstanceStates states;
    @Autowired
    LbHealthController controller;

    @AfterEach
    void resetTargets() {
        states.reset();
    }

    @Test
    void eagerLoadBuildsTheSupplierBeforeAnyRequest() {
        // Nothing has asked for an instance yet; LoadBalancerEagerContextInitializer did it on ApplicationReadyEvent.
        assertThat(loadBalancerClientFactory.getContextNames()).contains(SERVICE_ID);

        assertThat(supplier()).isInstanceOf(ObservableHealthCheckServiceInstanceListSupplier.class);
        await().atMost(Duration.ofSeconds(5)).until(() -> ports(current()).size() == PORTS.size());
    }

    @Test
    void getOnlyEverSeesCompleteRoundsWhileInstancesGoDown() {
        await().atMost(Duration.ofSeconds(5)).until(() -> ports(current()).size() == PORTS.size());
        int down = PORTS.get(3);
        int slow = PORTS.get(7);
        states.set(down, new TargetInstanceStates.State(false, Duration.ZERO));
        states.set(slow, new TargetInstanceStates.State(true, Duration.ofSeconds(2))); // > 500 ms read timeout

        List<List<Integer>> seen = new ArrayList<>();
        await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(20)).until(() -> {
            List<Integer> ports = ports(current());
            seen.add(ports);
            return ports.size() == PORTS.size() - 2;
        });

        List<Integer> healthy = PORTS.stream().filter(p -> p != down && p != slow).toList();
        assertThat(seen).as("every read is the previous complete round or the new complete round")
                .allSatisfy(ports -> assertThat(ports).isIn(PORTS, healthy, without(slow), without(down)));
        assertThat(seen.getLast()).containsExactlyInAnyOrderElementsOf(healthy);

        Map<Integer, Integer> chosen = controller.choose(50);
        assertThat(chosen.keySet()).doesNotContain(down, slow).allMatch(healthy::contains);
    }

    @Test
    void theLoadBalancedBusinessClientReachesATargetInstance() {
        await().atMost(Duration.ofSeconds(5)).until(() -> ports(current()).size() == PORTS.size());

        Object body = controller.call();

        assertThat(body).isInstanceOf(Map.class);
        assertThat(PORTS).contains((Integer) ((Map<?, ?>) body).get("port"));
    }

    private ServiceInstanceListSupplier supplier() {
        return loadBalancerClientFactory.getInstance(SERVICE_ID, ServiceInstanceListSupplier.class);
    }

    private List<ServiceInstance> current() {
        return supplier().get().next().block(Duration.ofSeconds(10));
    }

    private static List<Integer> ports(List<ServiceInstance> instances) {
        return instances == null ? List.of() : instances.stream().map(ServiceInstance::getPort).sorted().toList();
    }

    private static List<Integer> without(int port) {
        return PORTS.stream().filter(p -> p != port).toList();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
