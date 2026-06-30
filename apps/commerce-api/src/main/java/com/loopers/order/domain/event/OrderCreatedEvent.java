package com.loopers.order.domain.event;

public record OrderCreatedEvent(Long orderId, Long userId) {
}
