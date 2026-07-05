package com.loopers.support.event;

import com.loopers.order.domain.event.OrderCreatedEvent;
import com.loopers.product.domain.event.ProductViewedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

class UserActionLogListenerTest {

    private UserActionLogListener userActionLogListener;

    @BeforeEach
    void setUp() {
        userActionLogListener = new UserActionLogListener();
    }

    @DisplayName("OrderCreatedEvent를 수신할 때, 예외 없이 처리한다.")
    @Test
    void handlesOrderCreatedEvent_withoutException() {
        // arrange
        OrderCreatedEvent event = new OrderCreatedEvent(42L, 1L);

        // act & assert
        userActionLogListener.handleOrderCreated(event);
    }

    @DisplayName("ProductViewedEvent를 수신할 때, 예외 없이 처리한다.")
    @Test
    void handlesProductViewedEvent_withoutException() {
        // arrange
        ProductViewedEvent event = new ProductViewedEvent(7L, Instant.now());

        // act & assert
        userActionLogListener.handleProductViewed(event);
    }
}
