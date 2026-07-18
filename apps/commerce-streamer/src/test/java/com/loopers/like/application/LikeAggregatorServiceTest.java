package com.loopers.like.application;

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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LikeAggregatorServiceTest {

    private ProductMetricsRepository productMetricsRepository;
    private EventHandledRepository eventHandledRepository;
    private ApplicationEventPublisher eventPublisher;
    private LikeAggregatorService likeAggregatorService;

    @BeforeEach
    void setUp() {
        productMetricsRepository = mock(ProductMetricsRepository.class);
        eventHandledRepository = mock(EventHandledRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        likeAggregatorService = new LikeAggregatorService(
            productMetricsRepository, eventHandledRepository, eventPublisher,
            new RankingScorePolicy(new RankingWeightProperties(0.1, 0.2, 0.6)));
    }

    @DisplayName("handleLikeAdded를 호출할 때,")
    @Nested
    class HandleLikeAdded {

        @DisplayName("신규 eventId면, like_count를 증가시키고 랭킹 점수(+0.2) 이벤트를 발행한다.")
        @Test
        void incrementsLikeAndPublishes_whenNewEventId() {
            // arrange
            String eventId = "catalog-events:0:20";
            ProductMetricsModel metrics = new ProductMetricsModel(1L);
            when(eventHandledRepository.existsByEventId(eventId)).thenReturn(false);
            when(productMetricsRepository.findByProductId(1L)).thenReturn(Optional.of(metrics));

            // act
            likeAggregatorService.handleLikeAdded(eventId, 1L);

            // assert
            verify(eventHandledRepository).save(any(EventHandledModel.class));
            verify(productMetricsRepository).save(metrics);
            verify(eventPublisher).publishEvent(new RankingScoreEvent(1L, 0.2));
        }

        @DisplayName("이미 처리된 eventId면, 집계도 랭킹 이벤트도 하지 않는다.")
        @Test
        void skips_whenAlreadyHandled() {
            // arrange
            String eventId = "catalog-events:0:20";
            when(eventHandledRepository.existsByEventId(eventId)).thenReturn(true);

            // act
            likeAggregatorService.handleLikeAdded(eventId, 1L);

            // assert
            verify(eventHandledRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    @DisplayName("handleLikeCancelled를 호출할 때,")
    @Nested
    class HandleLikeCancelled {

        @DisplayName("신규 eventId면, like_count를 감소시키고 랭킹 점수(-0.2) 이벤트를 발행한다.")
        @Test
        void decrementsLikeAndPublishes_whenNewEventId() {
            // arrange
            String eventId = "catalog-events:0:21";
            ProductMetricsModel metrics = new ProductMetricsModel(1L);
            when(eventHandledRepository.existsByEventId(eventId)).thenReturn(false);
            when(productMetricsRepository.findByProductId(1L)).thenReturn(Optional.of(metrics));

            // act
            likeAggregatorService.handleLikeCancelled(eventId, 1L);

            // assert
            verify(productMetricsRepository).save(metrics);
            verify(eventPublisher).publishEvent(new RankingScoreEvent(1L, -0.2));
        }
    }
}
