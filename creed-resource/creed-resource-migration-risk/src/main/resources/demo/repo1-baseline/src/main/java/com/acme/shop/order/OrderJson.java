package com.acme.shop.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public class OrderJson {

    private final ObjectMapper mapper = new ObjectMapper();

    public String toJson(String id, long amount) {
        try {
            return mapper.writeValueAsString(Map.of("id", id, "amount", amount));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
