package com.acme.shop.order;

public class OrderValidator {

    public boolean valid(String orderId) {
        return orderId != null;
    }
}
