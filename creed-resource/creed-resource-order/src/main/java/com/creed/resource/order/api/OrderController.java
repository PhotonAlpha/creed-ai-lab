package com.creed.resource.order.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
@RequestMapping("/api/order")
@Slf4j
public class OrderController {
    public static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** In-memory store — adequate for local/manual testing; replaced by a real repository later. */
    private final ConcurrentMap<String, Order> store = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger(900);

    public OrderController() {
        seed("Alice", "Keyboard", 1, "42.50", "PAID");
        seed("Bob", "Mouse", 2, "7.00", "NEW");
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
                "service", "creed-resource-order",
                "subject", subjectOf(jwt),
                "items",
                List.of(
                        Map.of("id", "ORD-900", "total", 42.5),
                        Map.of("id", "ORD-901", "total", 7.0)));
    }

    /**
     * Bulk order feed for load/aggregation testing — returns a large, freshly generated order list.
     *
     * <p>Each order references an item {@code Item-1 .. Item-{itemRange}} (a superset of the catalog's
     * {@code Item-1 .. 200}, so some items have no catalog match), a random status across the lifecycle,
     * and a random quantity in {@code [1, 20]}. This deliberately produces orders that the aggregator's
     * fulfillment filter will reject (wrong status, missing item, or insufficient stock).
     *
     * @param size      number of orders to generate (default 500)
     * @param itemRange upper bound of the referenced item ids (default 250)
     * @param fail      when {@code "true"}, fault-injects a 500 so callers can exercise their error path
     */
    @GetMapping("/bulk")
    public ResponseEntity<List<Order>> bulk(
            @RequestParam(defaultValue = "500") int size,
            @RequestParam(defaultValue = "250") int itemRange,
            @RequestParam(defaultValue = "false") String fail) {
        if ("true".equalsIgnoreCase(fail)) {
            log.warn("bulk fault injection -> 500");
            return ResponseEntity.status(500).build();
        }
        log.info("bulk size={} itemRange={}", size, itemRange);
        String[] statuses = {"NEW", "PAID", "SHIPPED", "CANCELLED"};
        List<Order> list = new ArrayList<>(size);
        for (int i = 1; i <= size; i++) {
            int qty = random.nextInt(1, 21);
            String item = "Item-" + random.nextInt(1, itemRange + 1);
            String status = statuses[random.nextInt(statuses.length)];
            BigDecimal total = BigDecimal.valueOf(random.nextInt(1, 500)).movePointLeft(1).multiply(BigDecimal.valueOf(qty));
            list.add(new Order("ORD-B" + i, "cust-" + random.nextInt(1, 200), item, qty, total, status, Instant.now()));
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
                "service", "creed-resource-order",
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
                        "service", "creed-resource-order",
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
        result.put("service", "creed-resource-order");
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

    /** List every order currently held in the store. */
    @GetMapping
    public List<Order> list() {
        log.info("list");
        return new ArrayList<>(store.values());
    }

    /** Fetch a single order by id; 404 when it does not exist. */
    @GetMapping("/{id}")
    public ResponseEntity<Order> get(@PathVariable String id) {
        log.info("get:{}", id);
        Order order = store.get(id);
        return order != null ? ResponseEntity.ok(order) : ResponseEntity.notFound().build();
    }

    /** Create a new order; the server assigns the id, status and timestamp. */
    @PostMapping
    public ResponseEntity<Order> create(@RequestBody OrderRequest request) throws JsonProcessingException {
        log.info("get:{}", MAPPER.writeValueAsString(request));
        Order order = seed(request.customer(), request.item(), request.quantity(),
                request.total() != null ? request.total().toPlainString() : "0", "NEW");
        return ResponseEntity.ok(order);
    }

    /**
     * Checkout-create — the strict variant of {@link #create} used by creed-simple-metrics'
     * {@code POST /camel/api/checkout} chain: 400 with an error body (instead of silently defaulting)
     * when customer/item are blank, quantity is non-positive, or total is missing/negative. The order
     * starts in {@code NEW} like every created order; payment is authorized by the chain's next hop.
     */
    @PostMapping("/checkout")
    public ResponseEntity<?> checkout(@RequestBody OrderRequest request) throws JsonProcessingException {
        log.info("checkout:{}", MAPPER.writeValueAsString(request));
        if (request.customer() == null || request.customer().isBlank()
                || request.item() == null || request.item().isBlank()
                || request.quantity() <= 0
                || request.total() == null || request.total().signum() < 0) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "checkout requires non-blank 'customer' and 'item', a positive 'quantity' and a non-negative 'total'"));
        }
        Order order = seed(request.customer(), request.item(), request.quantity(),
                request.total().toPlainString(), "NEW");
        return ResponseEntity.status(201).body(order);
    }

    /**
     * Replace an existing order's mutable fields; 404 when it does not exist.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Order> update(@PathVariable String id, @RequestBody OrderRequest request) throws JsonProcessingException {
        log.info("update:{} request:{}", id, MAPPER.writeValueAsString(request));
        Order existing = store.get(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        Order updated = new Order(
                id,
                request.customer() != null ? request.customer() : existing.customer(),
                request.item() != null ? request.item() : existing.item(),
                request.quantity() != 0 ? request.quantity() : existing.quantity(),
                request.total() != null ? request.total() : existing.total(),
                request.status() != null ? request.status() : existing.status(),
                existing.createdAt());
        store.put(id, updated);
        return ResponseEntity.ok(updated);
    }

    /** Delete an order; 404 when it does not exist, 204 on success. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        log.info("delete:{}", id);
        return store.remove(id) != null ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private Order seed(String customer, String item, int quantity, String total, String status) {
        String id = "ORD-" + sequence.getAndIncrement();
        Order order = new Order(id, customer, item, quantity, new BigDecimal(total), status, Instant.now());
        store.put(id, order);
        return order;
    }

    private static String subjectOf(Jwt jwt) {
        return jwt != null ? jwt.getSubject() : "anonymous";
    }

    /** Request body for create/update — all fields optional so partial updates work. */
    public record OrderRequest(String customer, String item, int quantity, BigDecimal total, String status) {
    }
}
