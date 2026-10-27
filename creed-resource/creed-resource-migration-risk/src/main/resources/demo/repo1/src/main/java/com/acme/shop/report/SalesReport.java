package com.acme.shop.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class SalesReport {

    private final ObjectMapper mapper = new ObjectMapper();

    public String daily(String day, long orders, long revenue) {
        ObjectNode node = mapper.createObjectNode();
        node.put("day", day);
        node.put("orders", orders);
        node.put("revenue", revenue);
        return node.toString();
    }
}
