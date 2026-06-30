package com.loopers.view.application;

import com.loopers.eventhandled.domain.EventHandledModel;
import com.loopers.eventhandled.domain.EventHandledRepository;
import com.loopers.metrics.domain.ProductMetricsModel;
import com.loopers.metrics.domain.ProductMetricsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class ViewAggregatorService {

    private final ProductMetricsRepository productMetricsRepository;
    private final EventHandledRepository eventHandledRepository;

    @Transactional
    public void handleProductViewed(String eventId, Long productId, long occurredAtMillis) {
        if (eventHandledRepository.existsByEventId(eventId)) {
            return;
        }
        eventHandledRepository.save(new EventHandledModel(eventId));

        // catalog-events는 productId 키라 같은 상품이 단일 파티션에서 순차 처리 → read-modify-write 안전.
        // occurredAt 가드(최신만 반영)는 모델 applyView가 책임진다.
        ProductMetricsModel metrics = productMetricsRepository.findByProductId(productId)
            .orElseGet(() -> new ProductMetricsModel(productId));
        metrics.applyView(occurredAtMillis);
        productMetricsRepository.save(metrics);
    }
}
