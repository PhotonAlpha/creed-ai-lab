package com.acme.shop.order;

import java.util.ArrayList;
import java.util.List;

public class OrderService {

    private final List<String> orders = new ArrayList<>();

    public String placeOrder(String id) {
        String receipt = "R-" + id;
        orders.add(receipt);
        return receipt;
    }

    public boolean cancelOrder(String id) {
        String receipt = "R-" + id;
        return orders.remove(receipt);
    }

    /** Kept for the nightly report; superseded by PriceCalculator in the new code base. */
    public long legacyTotal(long net, long tax) {
        long total = net + tax;
        if (total < 0) {
            total = 0;
        }
        return total;
    }
}
