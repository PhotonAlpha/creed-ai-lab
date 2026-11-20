package com.creed.framework.tracker.it;

import io.micrometer.context.ContextSnapshotFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.zalando.logbook.spring.LogbookClientHttpRequestInterceptor;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootApplication
public class TrackerTestApplication {

    @RestController
    static class JourneyController {

        private final LogbookClientHttpRequestInterceptor logbook;

        JourneyController(LogbookClientHttpRequestInterceptor logbook) {
            this.logbook = logbook;
        }

        @PostMapping("/api/connection")
        String connection() {
            return "connected";
        }

        @GetMapping("/api/echo")
        String echo() {
            return "echo";
        }

        /** An outbound call made on another thread, the way veracode's Concurrently makes the Jira searches. */
        @GetMapping("/api/journey")
        String journey(HttpServletRequest request) throws Exception {
            String base = request.getScheme() + "://localhost:" + request.getLocalPort();
            RestClient client = RestClient.builder().baseUrl(base).requestInterceptor(logbook).build();
            Callable<String> call = ContextSnapshotFactory.builder().build().captureAll()
                    .wrap(() -> client.get().uri("/api/echo").retrieve().body(String.class));
            try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
                return "journey:" + threads.submit(call).get();
            }
        }

        @GetMapping("/api/big-header")
        String bigHeader(HttpServletResponse response) {
            response.setHeader("X-Big", "b".repeat(10_000));
            return "big";
        }
    }
}
