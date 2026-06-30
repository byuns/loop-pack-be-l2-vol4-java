package com.loopers.like.application;

import com.loopers.eventhandled.domain.EventHandledModel;
import com.loopers.eventhandled.domain.EventHandledRepository;
import com.loopers.metrics.domain.ProductMetricsModel;
import com.loopers.metrics.domain.ProductMetricsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class LikeAggregatorService {

    private final ProductMetricsRepository productMetricsRepository;
    private final EventHandledRepository eventHandledRepository;

    @Transactional
    public void handleLikeAdded(String eventId, Long productId) {
        if (eventHandledRepository.existsByEventId(eventId)) {
            return;
        }
        eventHandledRepository.save(new EventHandledModel(eventId));

        ProductMetricsModel metrics = productMetricsRepository.findByProductId(productId)
            .orElseGet(() -> new ProductMetricsModel(productId));
        metrics.incrementLikeCount();
        productMetricsRepository.save(metrics);
    }

    @Transactional
    public void handleLikeCancelled(String eventId, Long productId) {
        if (eventHandledRepository.existsByEventId(eventId)) {
            return;
        }
        eventHandledRepository.save(new EventHandledModel(eventId));

        ProductMetricsModel metrics = productMetricsRepository.findByProductId(productId)
            .orElseGet(() -> new ProductMetricsModel(productId));
        metrics.decrementLikeCount();
        productMetricsRepository.save(metrics);
    }
}
