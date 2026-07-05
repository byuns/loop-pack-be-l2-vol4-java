package com.loopers.product.domain.event;

import java.time.Instant;

/**
 * 상품 조회 이벤트. 유실 허용(조회 행동 로깅 + 조회수 집계) → Outbox 없이 Kafka로 직접 발행한다.
 * occurredAt은 Consumer의 "최신만 반영" 가드에 쓰인다.
 */
public record ProductViewedEvent(Long productId, Instant occurredAt) {
}
