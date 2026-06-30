package com.loopers.payment.application;

import com.loopers.eventhandled.domain.EventHandledModel;
import com.loopers.eventhandled.domain.EventHandledRepository;
import com.loopers.metrics.domain.ProductMetricsModel;
import com.loopers.metrics.domain.ProductMetricsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Service
public class SalesAggregatorService {

    private final ProductMetricsRepository productMetricsRepository;
    private final EventHandledRepository eventHandledRepository;

    @Transactional
    public void handleOrderConfirmed(String eventId, List<SalesItem> items) {
        if (eventHandledRepository.existsByEventId(eventId)) {
            return;
        }
        eventHandledRepository.save(new EventHandledModel(eventId));

        for (SalesItem item : items) {
            ProductMetricsModel metrics = productMetricsRepository.findByProductId(item.productId())
                .orElseGet(() -> new ProductMetricsModel(item.productId()));
            metrics.addSalesCount(item.quantity());
            productMetricsRepository.save(metrics);
        }
    }
}
