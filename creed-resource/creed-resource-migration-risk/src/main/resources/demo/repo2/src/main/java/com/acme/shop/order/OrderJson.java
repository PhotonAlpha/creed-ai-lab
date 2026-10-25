package com.acme.shop.order;

import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

public class OrderJson {

    private final JsonMapper mapper = JsonMapper.builder().build();

    public String toJson(String id, long amount) {
        // Jackson 3: JacksonException is unchecked, so there is nothing to catch here any more.
        return mapper.writeValueAsString(Map.of("id", id, "amount", amount));
    }
}
