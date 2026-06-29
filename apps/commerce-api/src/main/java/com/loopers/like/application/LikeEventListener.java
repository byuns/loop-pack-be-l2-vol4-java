package com.loopers.like.application;

import com.loopers.like.domain.event.LikeAddedEvent;
import com.loopers.like.domain.event.LikeCancelledEvent;
import com.loopers.product.domain.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@RequiredArgsConstructor
@Component
public class LikeEventListener {

    private final ProductRepository productRepository;

    // [fix] AFTER_COMMIT 시점엔 원본 커넥션이 아직 반환되지 않아 REQUIRES_NEW가 풀 고갈을 일으킴
    // @Async로 별도 스레드에서 실행해 원본 커넥션 반환 후 새 커넥션 획득
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleLikeAdded(LikeAddedEvent event) {
        productRepository.incrementLikeCount(event.productId());
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleLikeCancelled(LikeCancelledEvent event) {
        productRepository.decrementLikeCount(event.productId());
    }
}
