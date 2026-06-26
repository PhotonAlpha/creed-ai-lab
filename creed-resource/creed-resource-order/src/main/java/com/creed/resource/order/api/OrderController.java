package com.creed.resource.order.api;

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
