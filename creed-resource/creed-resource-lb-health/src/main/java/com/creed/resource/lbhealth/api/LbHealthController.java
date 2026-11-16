package com.creed.resource.lbhealth.api;

import com.creed.resource.lbhealth.config.LbHealthConfiguration;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Looks at the load balancer from the client side. */
@RestController
@RequestMapping("/lb")
@RequiredArgsConstructor
public class LbHealthController {

    private static final String SERVICE_ID = LbHealthConfiguration.TARGET_SERVICE_ID;

    private final LoadBalancerClientFactory loadBalancerClientFactory;
    private final LoadBalancerClient loadBalancerClient;
    private final RestTemplate loadBalancedRestTemplate;

    public record InstanceList(Instant at, int size, List<String> instances) {
    }

    /** Exactly what {@code RoundRobinLoadBalancer} sees: {@code supplier.get(request).next()}. */
    @GetMapping("/instances")
    public InstanceList instances() {
        ServiceInstanceListSupplier supplier =
                loadBalancerClientFactory.getInstance(SERVICE_ID, ServiceInstanceListSupplier.class);
        List<ServiceInstance> instances = supplier.get().next().block(Duration.ofSeconds(90));
        List<String> ids = instances == null ? List.of()
                : instances.stream().map(i -> i.getInstanceId() + "@" + i.getPort()).toList();
        return new InstanceList(Instant.now(), ids.size(), ids);
    }

    /** {@code n} choices through the load balancer, counted per port. */
    @GetMapping("/choose")
    public Map<Integer, Integer> choose(@RequestParam(defaultValue = "20") int n) {
        Map<Integer, Integer> perPort = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            ServiceInstance chosen = loadBalancerClient.choose(SERVICE_ID);
            perPort.merge(chosen == null ? -1 : chosen.getPort(), 1, Integer::sum);
        }
        return perPort;
    }

    /** A real call through the {@code @LoadBalanced} business client. */
    @GetMapping("/call")
    public Object call() {
        return loadBalancedRestTemplate.getForObject("http://" + SERVICE_ID + "/api/lb-health/target/whoami",
                Map.class);
    }
}
