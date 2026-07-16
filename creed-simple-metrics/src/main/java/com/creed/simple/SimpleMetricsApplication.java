package com.creed.simple;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportResource;

/**
 * Camel-on-Spring-Boot demo. All routes are declared with the classic <strong>Spring XML DSL</strong>
 * inside a single {@code <camelContext>} (see {@code src/main/resources/camel-context.xml}, imported
 * below) and served through the camel-servlet transport on the embedded Tomcat. A couple of routes are
 * self-contained ({@code /hello}, {@code /time}, {@code /echo}); {@code /catalog}, {@code /order},
 * {@code /aggregate} and {@code /aggregate-notify} call the downstream resource servers via the
 * camel-http component (mTLS connection manager + Spring Cloud LoadBalancer route planner wired in
 * {@link com.creed.simple.config.CamelConfig}), round-robining across the health-checked instances of
 * each service. The aggregate-notify route runs its multicast on thread pool {@code aggregatePoolA} and
 * wire-taps a notification onto {@code notificationPoolB} (both declared inline as {@code <threadPool>}).
 */
@SpringBootApplication(scanBasePackages = "com.creed")
@ImportResource("classpath:camel-context.xml")
public class SimpleMetricsApplication {
    public static void main(String[] args) {

        SpringApplication.run(SimpleMetricsApplication.class, args);
    }
}
