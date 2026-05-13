package com.creed.resource.order.api;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/order")
public class OrderController {

    @GetMapping("/items")
    public Map<String, Object> items(@AuthenticationPrincipal Jwt jwt) {
        return Map.of(
                "service", "creed-resource-order",
                "subject", jwt.getSubject(),
                "items",
                List.of(
                        Map.of("id", "ORD-900", "total", 42.5),
                        Map.of("id", "ORD-901", "total", 7.0)));
    }
}
