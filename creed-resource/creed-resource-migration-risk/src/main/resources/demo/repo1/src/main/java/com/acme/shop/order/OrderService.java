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
        if (id == null) {
            return false;
        }
        String receipt = "R-" + id;
        return orders.remove(receipt);
    }

    public int orderCount() {
        return orders.size();
    }

    /** Kept for the nightly report; superseded by PriceCalculator in the new code base. */
    public long legacyTotal(long net, long tax) {
        long total = net + tax;
        if (total < 0) {
            throw new IllegalArgumentException("negative total: " + total);
        }
        return total;
    }
}
