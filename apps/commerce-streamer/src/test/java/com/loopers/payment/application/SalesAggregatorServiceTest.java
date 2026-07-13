package com.loopers.payment.application;

import com.loopers.eventhandled.domain.EventHandledModel;
import com.loopers.eventhandled.domain.EventHandledRepository;
import com.loopers.metrics.domain.ProductMetricsRepository;
import com.loopers.ranking.domain.RankingScoreEvent;
import com.loopers.ranking.domain.RankingScorePolicy;
import com.loopers.ranking.domain.RankingWeightProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SalesAggregatorServiceTest {

    private ProductMetricsRepository productMetricsRepository;
    private EventHandledRepository eventHandledRepository;
    private ApplicationEventPublisher eventPublisher;
    private final RankingScorePolicy scorePolicy =
        new RankingScorePolicy(new RankingWeightProperties(0.1, 0.2, 0.6));
    private SalesAggregatorService salesAggregatorService;

    @BeforeEach
    void setUp() {
        productMetricsRepository = mock(ProductMetricsRepository.class);
        eventHandledRepository = mock(EventHandledRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        salesAggregatorService = new SalesAggregatorService(
            productMetricsRepository, eventHandledRepository, eventPublisher, scorePolicy);
    }

    @DisplayName("handleOrderConfirmed를 호출할 때,")
    @Nested
    class HandleOrderConfirmed {

        @DisplayName("신규 eventId면, item별 incrementSalesCount·랭킹 점수 이벤트를 발행하고 event_handled에 기록한다.")
        @Test
        void incrementsSalesCountPerItem_whenNewEventId() {
            // arrange
            String eventId = "order:123";
            when(eventHandledRepository.existsByEventId(eventId)).thenReturn(false);

            // act
            salesAggregatorService.handleOrderConfirmed(eventId, List.of(
                new SalesItem(1L, 2L, 1000L),
                new SalesItem(2L, 1L, 5000L)
            ));

            // assert
            verify(eventHandledRepository).save(any(EventHandledModel.class));
            verify(productMetricsRepository).incrementSalesCount(1L, 2L);
            verify(productMetricsRepository).incrementSalesCount(2L, 1L);
            verify(eventPublisher).publishEvent(new RankingScoreEvent(1L, scorePolicy.orderScore(1000L, 2L)));
            verify(eventPublisher).publishEvent(new RankingScoreEvent(2L, scorePolicy.orderScore(5000L, 1L)));
        }

        @DisplayName("이미 처리된 eventId면, 집계도 랭킹 이벤트도 하지 않고 중복 기록도 남기지 않는다.")
        @Test
        void skips_whenEventIdAlreadyHandled() {
            // arrange
            String eventId = "order:123";
            when(eventHandledRepository.existsByEventId(eventId)).thenReturn(true);

            // act
            salesAggregatorService.handleOrderConfirmed(eventId, List.of(new SalesItem(1L, 2L, 1000L)));

            // assert
            verify(eventHandledRepository, never()).save(any());
            verify(productMetricsRepository, never()).incrementSalesCount(anyLong(), anyLong());
            verify(eventPublisher, never()).publishEvent(any());
        }
    }
}
