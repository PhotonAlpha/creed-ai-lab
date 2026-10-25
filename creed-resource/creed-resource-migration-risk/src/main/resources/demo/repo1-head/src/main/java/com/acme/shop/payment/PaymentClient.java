package com.acme.shop.payment;

import org.springframework.web.client.RestTemplate;

public class PaymentClient {

    private final RestTemplate rest = new RestTemplate();
    private final String baseUrl;

    public PaymentClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String charge(String orderId, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        String url = baseUrl + "/charges?order=" + orderId + "&amount=" + amount;
        return rest.postForObject(url, null, String.class);
    }

    public String refund(String chargeId) {
        return rest.postForObject(baseUrl + "/refunds/" + chargeId, null, String.class);
    }
}
