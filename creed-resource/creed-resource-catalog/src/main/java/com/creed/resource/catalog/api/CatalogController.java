package com.creed.resource.catalog.api;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
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

    /**
     * Bulk catalog feed for load/aggregation testing — returns a large, freshly generated product list.
     *
     * <p>Products are named {@code Item-1 .. Item-{size}} (the convention the order service references)
     * with randomised stock in {@code [0, 15]}, so a downstream stock check filters out a realistic
     * fraction of orders.
     *
     * @param size number of products to generate (default 200)
     * @param fail when {@code "true"}, fault-injects a 500 so callers can exercise their error path
     */
    @GetMapping("/bulk")
    public ResponseEntity<List<Product>> bulk(
            @RequestParam(defaultValue = "200") int size,
            @RequestParam(defaultValue = "false") String fail) {
        if ("true".equalsIgnoreCase(fail)) {
            log.warn("bulk fault injection -> 500");
            return ResponseEntity.status(500).build();
        }
        log.info("bulk size={}", size);
        String[] categories = {"Stationery", "Electronics", "Hardware", "Grocery"};
        List<Product> list = new ArrayList<>(size);
        for (int i = 1; i <= size; i++) {
            int stock = random.nextInt(0, 16);
            BigDecimal price = BigDecimal.valueOf(random.nextInt(1, 500)).movePointLeft(1);
            list.add(new Product("CAT-B" + i, "Item-" + i, categories[i % categories.length], price, stock, Instant.now()));
        }
        return ResponseEntity.ok(list);
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

    /**
     * Issues a demo "session": two hand-built Set-Cookie response headers. JSESSIONID carries only
     * {@code Path} (a plain version-0 cookie); WSASID also carries {@code Domain} + {@code Max-Age}
     * and deliberately <b>no</b> {@code Expires} — "Max-Age without Expires" is exactly what makes
     * {@code java.net.HttpCookie.parse()} on the caller side guess cookie version 1 (RFC 2965), the
     * trigger for the {@code $Path="/"; $Domain=...} corruption demonstrated by creed-simple-metrics
     * {@code POST /camel/api/cookie-relay}.
     *
     * <p>The headers are built by hand (not with {@link org.springframework.http.ResponseCookie})
     * on purpose: {@code ResponseCookie.toString()} always pairs {@code Max-Age} with an
     * {@code Expires}, and an {@code Expires} makes {@code HttpCookie.parse()} guess version 0 —
     * masking the very bug this endpoint reproduces. This mimics non-Spring downstreams (older
     * servlet stacks, hand-rolled headers) that send {@code Max-Age} alone.
     *
     * @param domain the WSASID {@code Domain} attribute (defaults to the value seen in the production log)
     */
    @PostMapping("/session")
    public ResponseEntity<Map<String, Object>> session(@RequestParam(defaultValue = "api.github.com") String domain) {
        String sessionCookie = "JSESSIONID=" + token() + "; Path=/; HttpOnly";
        String affinityCookie = "WSASID=" + token() + "; Path=/; Domain=" + domain + "; Max-Age=3600; HttpOnly";
        log.info("session issued: [{}] [{}]", sessionCookie, affinityCookie);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie)
                .header(HttpHeaders.SET_COOKIE, affinityCookie)
                .body(Map.of(
                        "service", "creed-resource-catalog",
                        "setCookies", List.of(sessionCookie, affinityCookie),
                        "time", Instant.now().toString()));
    }

    /**
     * Echoes the request's Cookie header(s) both raw and as parsed by Tomcat's RFC 6265 cookie
     * processor. When the caller forwards cookies serialized with {@code HttpCookie.toString()}
     * (version 1), the parsed view shows the damage: {@code $Path} / {@code $Domain} come back as
     * cookies of their own and the WSASID value keeps its surrounding quotes, so any lookup by
     * cookie name/value on this side misses.
     */
    @PostMapping("/echo")
    public Map<String, Object> echo(HttpServletRequest request,
                                    @RequestBody(required = false) Map<String, Object> body) {
        List<String> rawCookieHeaders = Collections.list(request.getHeaders(HttpHeaders.COOKIE));
        List<String> parsedCookies = request.getCookies() == null ? List.of()
                : Arrays.stream(request.getCookies()).map(c -> c.getName() + "=" + c.getValue()).toList();
        log.info("echo rawCookieHeaders={} parsedCookies={}", rawCookieHeaders, parsedCookies);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", "creed-resource-catalog");
        result.put("rawCookieHeaders", rawCookieHeaders);
        result.put("parsedCookies", parsedCookies);
        result.put("body", body);
        result.put("time", Instant.now().toString());
        return result;
    }

    private static String token() {
        byte[] buf = new byte[24];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /**
     * Reserve (and atomically decrement) stock for a checkout — the first hop of creed-simple-metrics'
     * {@code POST /camel/api/checkout} chain. Returns a price quote the caller feeds into the order
     * step. 400 blank sku / non-positive quantity, 404 unknown sku, 409 insufficient stock (body
     * reports the shortfall). Deliberately no compensation endpoint: if a later checkout step fails,
     * the reservation stays — an acceptable simplification for this in-memory demo store.
     */
    @PostMapping("/reserve")
    public ResponseEntity<Map<String, Object>> reserve(@RequestBody ReserveRequest request) throws JacksonException {
        log.info("reserve:{}", MAPPER.writeValueAsString(request));
        if (request.sku() == null || request.sku().isBlank() || request.quantity() <= 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "reserve requires a non-blank 'sku' and a positive 'quantity'"));
        }
        // computeIfPresent is atomic per key, so check-and-decrement cannot interleave with another reserve
        boolean[] insufficient = new boolean[1];
        Product reserved = store.computeIfPresent(request.sku(), (sku, existing) -> {
            if (existing.stock() < request.quantity()) {
                insufficient[0] = true;
                return existing;
            }
            return new Product(sku, existing.name(), existing.category(), existing.price(),
                    existing.stock() - request.quantity(), existing.createdAt());
        });
        if (reserved == null) {
            return ResponseEntity.notFound().build();
        }
        if (insufficient[0]) {
            log.warn("reserve rejected: sku={} requested={} available={}",
                    request.sku(), request.quantity(), reserved.stock());
            return ResponseEntity.status(409).body(Map.of(
                    "error", "insufficient stock",
                    "sku", request.sku(),
                    "requested", request.quantity(),
                    "available", reserved.stock()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", "creed-resource-catalog");
        result.put("sku", reserved.sku());
        result.put("name", reserved.name());
        result.put("quantity", request.quantity());
        result.put("unitPrice", reserved.price());
        result.put("total", reserved.price().multiply(BigDecimal.valueOf(request.quantity())));
        result.put("remainingStock", reserved.stock());
        result.put("reservedAt", Instant.now().toString());
        return ResponseEntity.ok(result);
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
    public ResponseEntity<Product> create(@RequestBody ProductRequest request) throws JacksonException {
        log.info("create:{}", MAPPER.writeValueAsString(request));
        Product product = seed(request.name(), request.category(),
                request.price() != null ? request.price().toPlainString() : "0", request.stock());
        return ResponseEntity.ok(product);
    }

    /** Replace an existing product's mutable fields; 404 when it does not exist. */
    @PutMapping("/{sku}")
    public ResponseEntity<Product> update(@PathVariable String sku, @RequestBody ProductRequest request) throws JacksonException {
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

    /** Request body for {@code POST /reserve}. */
    public record ReserveRequest(String sku, int quantity) {
    }
}
