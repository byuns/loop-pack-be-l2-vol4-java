package com.loopers.payment.application;

import com.loopers.payment.domain.event.PaymentConfirmedEvent;
import com.loopers.payment.domain.event.PaymentFailedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PaymentEventListenerTest {

    private PaymentEventListener paymentEventListener;

    @BeforeEach
    void setUp() {
        paymentEventListener = new PaymentEventListener();
    }

    @DisplayName("PaymentConfirmedEvent를 수신할 때,")
    @Nested
    class HandlePaymentConfirmed {

        @DisplayName("예외 없이 처리한다.")
        @Test
        void handlesPaymentConfirmedEvent_withoutException() {
            // arrange
            PaymentConfirmedEvent event = new PaymentConfirmedEvent(1L, 50000L);

            // act & assert
            paymentEventListener.handlePaymentConfirmed(event);
        }
    }

    @DisplayName("PaymentFailedEvent를 수신할 때,")
    @Nested
    class HandlePaymentFailed {

        @DisplayName("예외 없이 처리한다.")
        @Test
        void handlesPaymentFailedEvent_withoutException() {
            // arrange
            PaymentFailedEvent event = new PaymentFailedEvent(1L);

            // act & assert
            paymentEventListener.handlePaymentFailed(event);
        }
    }
}
