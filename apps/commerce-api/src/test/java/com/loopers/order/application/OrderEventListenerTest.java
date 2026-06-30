package com.loopers.order.application;

import com.loopers.order.domain.event.OrderCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderEventListenerTest {

    private OrderEventListener orderEventListener;

    @BeforeEach
    void setUp() {
        orderEventListener = new OrderEventListener();
    }

    @DisplayName("OrderCreatedEvent를 수신할 때, 예외 없이 처리한다.")
    @Test
    void handlesOrderCreatedEvent_withoutException() {
        // arrange
        OrderCreatedEvent event = new OrderCreatedEvent(42L, 1L);

        // act & assert
        orderEventListener.handleOrderCreated(event);
    }
}
