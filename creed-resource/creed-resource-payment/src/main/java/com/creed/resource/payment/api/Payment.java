package com.creed.resource.payment.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Simple in-memory payment model used by {@link PaymentController} for local testing.
 *
 * @param id        server-assigned identifier (e.g. {@code PAY-700})
 * @param orderId   the order this payment settles (e.g. {@code ORD-900} from creed-resource-order)
 * @param customer  free-form customer name
 * @param amount    payment amount
 * @param currency  ISO 4217 currency code (e.g. {@code SGD})
 * @param method    payment method (CARD / BANK_TRANSFER / WALLET)
 * @param status    lifecycle status (PENDING / AUTHORIZED / CAPTURED / REFUNDED / FAILED / CANCELLED)
 * @param createdAt creation timestamp
 */
public record Payment(
        String id,
        String orderId,
        String customer,
        BigDecimal amount,
        String currency,
        String method,
        String status,
        Instant createdAt) {

    /** Returns a copy of this payment with only the status replaced. */
    public Payment withStatus(String newStatus) {
        return new Payment(id, orderId, customer, amount, currency, method, newStatus, createdAt);
    }
}
