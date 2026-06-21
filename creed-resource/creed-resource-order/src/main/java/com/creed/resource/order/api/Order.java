package com.creed.resource.order.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Simple in-memory order model used by {@link OrderController} for local testing.
 *
 * @param id        server-assigned identifier (e.g. {@code ORD-900})
 * @param customer  free-form customer name
 * @param item      ordered item description
 * @param quantity  number of units ordered
 * @param total     order total amount
 * @param status    lifecycle status (NEW / PAID / SHIPPED / CANCELLED)
 * @param createdAt creation timestamp
 */
public record Order(
        String id,
        String customer,
        String item,
        int quantity,
        BigDecimal total,
        String status,
        Instant createdAt) {
}
