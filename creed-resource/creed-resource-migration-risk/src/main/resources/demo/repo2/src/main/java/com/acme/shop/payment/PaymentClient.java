package com.acme.shop.payment;

import org.springframework.web.client.RestClient;

public class PaymentClient {

    private final RestClient rest;
    private final String baseUrl;

    public PaymentClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.rest = RestClient.create(baseUrl);
    }

    public String charge(String orderId, long amount) {
        return rest.post().uri("/charges?order={order}&amount={amount}", orderId, amount)
                .retrieve().body(String.class);
    }

    public String refund(String chargeId) {
        return rest.post().uri("/refunds/{id}", chargeId).retrieve().body(String.class);
    }
}
