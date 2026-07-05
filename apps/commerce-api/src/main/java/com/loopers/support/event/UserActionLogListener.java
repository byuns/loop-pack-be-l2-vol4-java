package com.loopers.support.event;

import com.loopers.order.domain.event.OrderCreatedEvent;
import com.loopers.product.domain.event.ProductViewedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 유저 행동(조회·주문 등)을 도메인 이벤트로 받아 서버 레벨에서 통합 로깅한다.
 * 도메인은 전용 이벤트를 발행(타입 안전)하고, 행동 로그라는 cross-cutting 관심사는 이 리스너 한곳에 모은다.
 */
@Component
public class UserActionLogListener {

    private static final Logger log = LoggerFactory.getLogger(UserActionLogListener.class);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("[USER_ACTION] action=ORDER userId={} orderId={}", event.userId(), event.orderId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleProductViewed(ProductViewedEvent event) {
        log.info("[USER_ACTION] action=PRODUCT_VIEW productId={} at={}", event.productId(), event.occurredAt());
    }
}
