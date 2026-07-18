package com.loopers.view.application;

import com.loopers.eventhandled.domain.EventHandledModel;
import com.loopers.eventhandled.domain.EventHandledRepository;
import com.loopers.metrics.domain.ProductMetricsModel;
import com.loopers.metrics.domain.ProductMetricsRepository;
import com.loopers.ranking.domain.RankingScoreEvent;
import com.loopers.ranking.domain.RankingScorePolicy;
import com.loopers.ranking.domain.RankingWeightProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ViewAggregatorServiceTest {

    private ProductMetricsRepository productMetricsRepository;
    private EventHandledRepository eventHandledRepository;
    private ApplicationEventPublisher eventPublisher;
    private ViewAggregatorService viewAggregatorService;

    @BeforeEach
    void setUp() {
        productMetricsRepository = mock(ProductMetricsRepository.class);
        eventHandledRepository = mock(EventHandledRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        viewAggregatorService = new ViewAggregatorService(
            productMetricsRepository, eventHandledRepository, eventPublisher,
            new RankingScorePolicy(new RankingWeightProperties(0.1, 0.2, 0.6)));
    }

    @DisplayName("handleProductViewed를 호출할 때,")
    @Nested
    class HandleProductViewed {

        @DisplayName("신규 eventId면, view_count를 반영하고 event_handled에 기록하며 랭킹 점수(0.1) 이벤트를 발행한다.")
        @Test
        void appliesViewAndRecords_whenNewEventId() {
            // arrange
            String eventId = "catalog-events:0:10";
            ProductMetricsModel metrics = new ProductMetricsModel(1L);
            when(eventHandledRepository.existsByEventId(eventId)).thenReturn(false);
            when(productMetricsRepository.findByProductId(1L)).thenReturn(Optional.of(metrics));

            // act
            viewAggregatorService.handleProductViewed(eventId, 1L, 1000L);

            // assert
            assertThat(metrics.getViewCount()).isEqualTo(1L);
            verify(eventHandledRepository).save(any(EventHandledModel.class));
            verify(productMetricsRepository).save(metrics);
            verify(eventPublisher).publishEvent(new RankingScoreEvent(1L, 0.1));
        }

        @DisplayName("이미 처리된 eventId면, 아무 집계도 하지 않고 중복 기록도 랭킹 이벤트도 남기지 않는다.")
        @Test
        void skips_whenEventIdAlreadyHandled() {
            // arrange
            String eventId = "catalog-events:0:10";
            when(eventHandledRepository.existsByEventId(eventId)).thenReturn(true);

            // act
            viewAggregatorService.handleProductViewed(eventId, 1L, 1000L);

            // assert
            verify(eventHandledRepository, never()).save(any());
            verify(productMetricsRepository, never()).findByProductId(any());
            verify(productMetricsRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @DisplayName("product_metrics가 없으면, 새로 생성한 뒤 view_count를 반영한다.")
        @Test
        void createsMetrics_whenNotExists() {
            // arrange
            String eventId = "catalog-events:0:10";
            when(eventHandledRepository.existsByEventId(eventId)).thenReturn(false);
            when(productMetricsRepository.findByProductId(99L)).thenReturn(Optional.empty());

            // act
            viewAggregatorService.handleProductViewed(eventId, 99L, 1000L);

            // assert
            verify(productMetricsRepository).save(any(ProductMetricsModel.class));
        }
    }
}
