package com.acme.shop.order;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderServiceTest {

    @Test
    void placedOrderCanBeCancelled() {
        OrderService service = new OrderService();
        service.placeOrder("42");
        assertThat(service.cancelOrder("42")).isTrue();
    }

    @Test
    void unknownOrderIsNotCancelled() {
        assertThat(new OrderService().cancelOrder(null)).isFalse();
    }
}
