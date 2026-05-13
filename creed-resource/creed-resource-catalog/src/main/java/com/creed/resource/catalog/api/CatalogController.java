package com.creed.resource.catalog.api;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    @GetMapping("/items")
    public Map<String, Object> items(@AuthenticationPrincipal Jwt jwt) {
        return Map.of(
                "service", "creed-resource-catalog",
                "subject", jwt.getSubject(),
                "items",
                List.of(
                        Map.of("sku", "CAT-1", "name", "Notebook"),
                        Map.of("sku", "CAT-2", "name", "Pen")));
    }
}
