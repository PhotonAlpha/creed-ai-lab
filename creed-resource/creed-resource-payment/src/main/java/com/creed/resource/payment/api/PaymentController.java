package com.creed.resource.payment.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Payment API — the settlement twin of the order module's {@code OrderController}. Same
 * conventions: in-memory store, server-assigned ids, {@code /ping} as the LB health-check path,
 * and a null-safe {@code @AuthenticationPrincipal Jwt} under the local {@code permitAll} setup.
 *
 * <p>On top of plain CRUD it models the payment lifecycle explicitly:
 * {@code PENDING → AUTHORIZED → CAPTURED → REFUNDED}, with {@code FAILED}/{@code CANCELLED}
 * as terminal side exits. The transition endpoints ({@code /authorize}, {@code /capture},
 * {@code /refund}) return {@code 409 Conflict} when the payment is not in the required
 * predecessor state, so aggregators can exercise a realistic error path.
 */
@RestController
@RequestMapping("/api/payment")
@Slf4j
public class PaymentController {
    public static final JsonMapper MAPPER = JsonMapper.builder().build();

    static final String PENDING = "PENDING";
    static final String AUTHORIZED = "AUTHORIZED";
    static final String CAPTURED = "CAPTURED";
    static final String REFUNDED = "REFUNDED";
    static final String CANCELLED = "CANCELLED";

    private static final Set<String> METHODS = Set.of("CARD", "BANK_TRANSFER", "WALLET");

    /** In-memory store — adequate for local/manual testing; replaced by a real repository later. */
    private final ConcurrentMap<String, Payment> store = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger(700);

    public PaymentController() {
        seed("ORD-900", "Alice", "42.50", "SGD", "CARD", CAPTURED);
        seed("ORD-901", "Bob", "14.00", "SGD", "WALLET", PENDING);
    }

    /** Lightweight liveness/echo endpoint — the LB health-check path, same shape as the twins'. */
    @GetMapping("/ping")
    public Map<String, Object> ping(@AuthenticationPrincipal Jwt jwt) {
        log.info("ping");
        return Map.of(
                "service", "creed-resource-payment",
                "status", "UP",
                "subject", subjectOf(jwt),
                "time", Instant.now().toString());
    }

    /** List payments, optionally filtered by order id and/or status. */
    @GetMapping
    public List<Payment> list(
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String status) {
        log.info("list orderId={} status={}", orderId, status);
        List<Payment> result = new ArrayList<>();
        for (Payment payment : store.values()) {
            boolean matches = (orderId == null || orderId.equals(payment.orderId()))
                    && (status == null || status.equalsIgnoreCase(payment.status()));
            if (matches) {
                result.add(payment);
            }
        }
        return result;
    }

    /** Fetch a single payment by id; 404 when it does not exist. */
    @GetMapping("/{id}")
    public ResponseEntity<Payment> get(@PathVariable String id) {
        log.info("get:{}", id);
        Payment payment = store.get(id);
        return payment != null ? ResponseEntity.ok(payment) : ResponseEntity.notFound().build();
    }

    /**
     * Initiate a payment for an order; the server assigns the id and timestamp and the payment
     * starts in {@code PENDING}. 400 when the amount is missing/non-positive or the method unknown.
     */
    @PostMapping
    public ResponseEntity<Payment> create(@RequestBody PaymentRequest request) throws JsonProcessingException {
        log.info("create:{}", MAPPER.writeValueAsString(request));
        if (request.amount() == null || request.amount().signum() <= 0) {
            return ResponseEntity.badRequest().build();
        }
        String method = request.method() != null ? request.method().toUpperCase() : "CARD";
        if (!METHODS.contains(method)) {
            return ResponseEntity.badRequest().build();
        }
        Payment payment = seed(
                request.orderId(),
                request.customer(),
                request.amount().toPlainString(),
                request.currency() != null ? request.currency() : "SGD",
                method,
                PENDING);
        return ResponseEntity.status(HttpStatus.CREATED).body(payment);
    }

    /**
     * Create <em>and authorize</em> a payment in ONE call — for creed-simple-metrics'
     * {@code POST /camel/api/checkout} chain. The merge is deliberate, not convenience: each instance
     * has its own in-memory store, so a separate create-then-authorize pair routed through the
     * round-robin LB could land on different instances and 404. Same 400 validation as
     * {@link #create}; the stored payment goes straight to {@code AUTHORIZED} (capture/refund/cancel
     * then apply as usual).
     */
    @PostMapping("/checkout")
    public ResponseEntity<Payment> checkout(@RequestBody PaymentRequest request) throws JsonProcessingException {
        log.info("checkout:{}", MAPPER.writeValueAsString(request));
        if (request.amount() == null || request.amount().signum() <= 0) {
            return ResponseEntity.badRequest().build();
        }
        String method = request.method() != null ? request.method().toUpperCase() : "CARD";
        if (!METHODS.contains(method)) {
            return ResponseEntity.badRequest().build();
        }
        Payment payment = seed(
                request.orderId(),
                request.customer(),
                request.amount().toPlainString(),
                request.currency() != null ? request.currency() : "SGD",
                method,
                PENDING);
        Payment authorized = payment.withStatus(AUTHORIZED);
        store.put(authorized.id(), authorized);
        return ResponseEntity.status(HttpStatus.CREATED).body(authorized);
    }

    /** Replace an existing payment's mutable fields; 404 when it does not exist. */
    @PutMapping("/{id}")
    public ResponseEntity<Payment> update(@PathVariable String id, @RequestBody PaymentRequest request)
            throws JsonProcessingException {
        log.info("update:{} request:{}", id, MAPPER.writeValueAsString(request));
        Payment existing = store.get(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        Payment updated = new Payment(
                id,
                request.orderId() != null ? request.orderId() : existing.orderId(),
                request.customer() != null ? request.customer() : existing.customer(),
                request.amount() != null ? request.amount() : existing.amount(),
                request.currency() != null ? request.currency() : existing.currency(),
                request.method() != null ? request.method().toUpperCase() : existing.method(),
                request.status() != null ? request.status().toUpperCase() : existing.status(),
                existing.createdAt());
        store.put(id, updated);
        return ResponseEntity.ok(updated);
    }

    /** Authorize a PENDING payment; 404 unknown id, 409 when not PENDING. */
    @PostMapping("/{id}/authorize")
    public ResponseEntity<Payment> authorize(@PathVariable String id) {
        return transition(id, PENDING, AUTHORIZED);
    }

    /** Capture an AUTHORIZED payment; 404 unknown id, 409 when not AUTHORIZED. */
    @PostMapping("/{id}/capture")
    public ResponseEntity<Payment> capture(@PathVariable String id) {
        return transition(id, AUTHORIZED, CAPTURED);
    }

    /** Refund a CAPTURED payment; 404 unknown id, 409 when not CAPTURED. */
    @PostMapping("/{id}/refund")
    public ResponseEntity<Payment> refund(@PathVariable String id) {
        return transition(id, CAPTURED, REFUNDED);
    }

    /**
     * Cancel a payment that has not been captured yet: PENDING/AUTHORIZED become CANCELLED (200);
     * 404 unknown id, 409 once captured/refunded — money already moved, use {@code /refund} instead.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Payment> cancel(@PathVariable String id) {
        log.info("cancel:{}", id);
        Payment payment = store.get(id);
        if (payment == null) {
            return ResponseEntity.notFound().build();
        }
        if (!PENDING.equals(payment.status()) && !AUTHORIZED.equals(payment.status())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(payment);
        }
        Payment cancelled = payment.withStatus(CANCELLED);
        store.put(id, cancelled);
        return ResponseEntity.ok(cancelled);
    }

    private ResponseEntity<Payment> transition(String id, String requiredStatus, String targetStatus) {
        log.info("{} -> {} : {}", requiredStatus, targetStatus, id);
        Payment payment = store.get(id);
        if (payment == null) {
            return ResponseEntity.notFound().build();
        }
        if (!requiredStatus.equals(payment.status())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(payment);
        }
        Payment moved = payment.withStatus(targetStatus);
        store.put(id, moved);
        return ResponseEntity.ok(moved);
    }

    private Payment seed(String orderId, String customer, String amount, String currency, String method, String status) {
        String id = "PAY-" + sequence.getAndIncrement();
        Payment payment = new Payment(id, orderId, customer, new BigDecimal(amount), currency, method, status, Instant.now());
        store.put(id, payment);
        return payment;
    }

    private static String subjectOf(Jwt jwt) {
        return jwt != null ? jwt.getSubject() : "anonymous";
    }

    /** Request body for create/update — all fields optional so partial updates work. */
    public record PaymentRequest(
            String orderId, String customer, BigDecimal amount, String currency, String method, String status) {
    }
}
