package com.acme.shop.order.processing;

import com.acme.shop.order.OrderService;

public class OrderProcessor {

    private final OrderService service = new OrderService();

    public String process(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId is required");
        }
        String normalized = orderId.trim().toUpperCase();
        String receipt = service.placeOrder(normalized);
        return receipt + ":" + normalized.length();
    }
}
