package com.loopers.payment.domain.event;

public record PaymentConfirmedEvent(Long orderId, Long amount) {
}
