package com.loopers.payment.application;

import com.loopers.payment.domain.event.PaymentConfirmedEvent;
import com.loopers.payment.domain.event.PaymentFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePaymentConfirmed(PaymentConfirmedEvent event) {
        log.info("[PAYMENT_CONFIRMED] orderId={} amount={}", event.orderId(), event.amount());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePaymentFailed(PaymentFailedEvent event) {
        log.info("[PAYMENT_FAILED] orderId={}", event.orderId());
    }
}
