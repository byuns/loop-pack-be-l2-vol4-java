package com.loopers.payment.application;

import com.loopers.eventhandled.domain.EventHandledModel;
import com.loopers.eventhandled.domain.EventHandledRepository;
import com.loopers.metrics.domain.ProductMetricsRepository;
import com.loopers.ranking.domain.RankingScoreEvent;
import com.loopers.ranking.domain.RankingScorePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Service
public class SalesAggregatorService {

    private final ProductMetricsRepository productMetricsRepository;
    private final EventHandledRepository eventHandledRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final RankingScorePolicy scorePolicy;

    @Transactional
    public void handleOrderConfirmed(String eventId, List<SalesItem> items) {
        if (eventHandledRepository.existsByEventId(eventId)) {
            return;
        }
        eventHandledRepository.save(new EventHandledModel(eventId));

        // 원자 UPSERT로 누적 — 같은 productId가 다른 파티션·인스턴스에서 동시에 갱신돼도 lost update 없음
        for (SalesItem item : items) {
            productMetricsRepository.incrementSalesCount(item.productId(), item.quantity());
            eventPublisher.publishEvent(new RankingScoreEvent(
                item.productId(), scorePolicy.orderScore(item.price(), item.quantity())));
        }
    }
}
