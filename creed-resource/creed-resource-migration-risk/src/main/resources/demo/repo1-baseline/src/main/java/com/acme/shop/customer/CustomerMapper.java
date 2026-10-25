package com.acme.shop.customer;

import java.util.Map;

public class CustomerMapper {

    public Map<String, String> toView(String id, String name, String email) {
        return Map.of("id", id, "name", name, "email", email.toLowerCase());
    }
}
