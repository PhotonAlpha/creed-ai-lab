package com.creed.resource.catalog.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/catalog")
@Slf4j
public class CatalogController {
    public static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** In-memory store — adequate for local/manual testing; replaced by a real repository later. */
    private final ConcurrentMap<String, Product> store = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger(1);

    public CatalogController() {
        seed("Notebook", "Stationery", "3.50", 100);
        seed("Pen", "Stationery", "1.20", 500);
    }

    public static final Random random = new Random();
    /** Original sample endpoint — kept for backwards compatibility; JWT is optional under permitAll. */
    @GetMapping("/items")
    public Map<String, Object> items(@AuthenticationPrincipal Jwt jwt) {
        log.info("get items");
        try {
            int i = random.nextInt(0, 8);
            TimeUnit.SECONDS.sleep(i);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return Map.of(
                "service", "creed-resource-catalog",
                "subject", subjectOf(jwt),
                "items",
                List.of(
                        Map.of("sku", "CAT-1", "name", "Notebook"),
                        Map.of("sku", "CAT-2", "name", "Pen")));
    }

    /** Lightweight liveness/echo endpoint — handy for verifying the HTTPS listener is up. */
    @GetMapping("/ping")
    public Map<String, Object> ping(@AuthenticationPrincipal Jwt jwt) {
        log.info("ping");
        try {
            TimeUnit.MILLISECONDS.sleep(500);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return Map.of(
                "service", "creed-resource-catalog",
                "status", "UP",
                "subject", subjectOf(jwt),
                "time", Instant.now().toString());
    }

    /** List every product currently held in the store. */
    @GetMapping
    public List<Product> list() {
        log.info("list");
        return new ArrayList<>(store.values());
    }

    /** Fetch a single product by sku; 404 when it does not exist. */
    @GetMapping("/{sku}")
    public ResponseEntity<Product> get(@PathVariable String sku) {
        log.info("get:{}", sku);
        Product product = store.get(sku);
        return product != null ? ResponseEntity.ok(product) : ResponseEntity.notFound().build();
    }

    /** Create a new product; the server assigns the sku and timestamp. */
    @PostMapping
    public ResponseEntity<Product> create(@RequestBody ProductRequest request) throws JsonProcessingException {
        log.info("create:{}", MAPPER.writeValueAsString(request));
        Product product = seed(request.name(), request.category(),
                request.price() != null ? request.price().toPlainString() : "0", request.stock());
        return ResponseEntity.ok(product);
    }

    /** Replace an existing product's mutable fields; 404 when it does not exist. */
    @PutMapping("/{sku}")
    public ResponseEntity<Product> update(@PathVariable String sku, @RequestBody ProductRequest request) throws JsonProcessingException {
        log.info("update:{} request:{}", sku, MAPPER.writeValueAsString(request));
        Product existing = store.get(sku);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        Product updated = new Product(
                sku,
                request.name() != null ? request.name() : existing.name(),
                request.category() != null ? request.category() : existing.category(),
                request.price() != null ? request.price() : existing.price(),
                request.stock() != 0 ? request.stock() : existing.stock(),
                existing.createdAt());
        store.put(sku, updated);
        return ResponseEntity.ok(updated);
    }

    /** Delete a product; 404 when it does not exist, 204 on success. */
    @DeleteMapping("/{sku}")
    public ResponseEntity<Void> delete(@PathVariable String sku) {
        log.info("delete:{}", sku);
        return store.remove(sku) != null ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private Product seed(String name, String category, String price, int stock) {
        String sku = "CAT-" + sequence.getAndIncrement();
        Product product = new Product(sku, name, category, new BigDecimal(price), stock, Instant.now());
        store.put(sku, product);
        return product;
    }

    private static String subjectOf(Jwt jwt) {
        return jwt != null ? jwt.getSubject() : "anonymous";
    }

    /** Request body for create/update — all fields optional so partial updates work. */
    public record ProductRequest(String name, String category, BigDecimal price, int stock) {
    }
}
