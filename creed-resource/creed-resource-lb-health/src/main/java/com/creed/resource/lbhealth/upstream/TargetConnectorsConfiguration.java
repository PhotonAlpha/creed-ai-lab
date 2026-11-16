package com.creed.resource.lbhealth.upstream;

import com.creed.resource.lbhealth.config.LbHealthConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

/**
 * Turns this app into its own 10 target instances: one extra HTTP connector per instance of
 * {@value LbHealthConfiguration#TARGET_SERVICE_ID} registered in the simple discovery client on localhost. The
 * ports come from discovery, so the registry and the listeners cannot drift apart. Off with
 * {@code creed.lb-health.target.embedded=false} when the instances are real services.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "creed.lb-health.target.embedded", havingValue = "true", matchIfMissing = true)
public class TargetConnectorsConfiguration {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1");

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> targetInstanceConnectors(
            SimpleDiscoveryProperties discovery) {
        return factory -> {
            List<Integer> ports = discovery.getInstances()
                    .getOrDefault(LbHealthConfiguration.TARGET_SERVICE_ID, List.of()).stream()
                    .filter(instance -> LOCAL_HOSTS.contains(instance.getHost()))
                    .map(instance -> instance.getPort())
                    .filter(port -> port > 0 && port != factory.getPort())
                    .distinct()
                    .toList();
            for (int port : ports) {
                Connector connector = new Connector();
                connector.setPort(port);
                // Ten connectors each with Tomcat's default pool (10 spare / 200 max) would be 100 idle threads.
                connector.setProperty("minSpareThreads", "1");
                connector.setProperty("maxThreads", "20");
                factory.addAdditionalConnectors(connector);
            }
            log.info("[LB-HEALTH] embedded target instances on ports {}", ports);
        };
    }
}
