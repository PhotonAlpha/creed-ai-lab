package com.acme.shop.order;

public class OrderProcessor {

    private final OrderService service = new OrderService();

    public String handle(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId is required");
        }
        String normalized = orderId.trim().toUpperCase();
        String receipt = service.placeOrder(normalized);
        return receipt + ":" + normalized.length();
    }
}
