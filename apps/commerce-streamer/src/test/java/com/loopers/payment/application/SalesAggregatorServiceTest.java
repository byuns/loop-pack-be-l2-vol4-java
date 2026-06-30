package com.loopers.payment.application;

import com.loopers.eventhandled.domain.EventHandledModel;
import com.loopers.eventhandled.domain.EventHandledRepository;
import com.loopers.metrics.domain.ProductMetricsModel;
import com.loopers.metrics.domain.ProductMetricsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SalesAggregatorServiceTest {

    private ProductMetricsRepository productMetricsRepository;
    private EventHandledRepository eventHandledRepository;
    private SalesAggregatorService salesAggregatorService;

    @BeforeEach
    void setUp() {
        productMetricsRepository = mock(ProductMetricsRepository.class);
        eventHandledRepository = mock(EventHandledRepository.class);
        salesAggregatorService = new SalesAggregatorService(productMetricsRepository, eventHandledRepository);
    }

    @DisplayName("handleOrderConfirmed를 호출할 때,")
    @Nested
    class HandleOrderConfirmed {

        @DisplayName("신규 eventId면, items의 각 productId별 sales_count를 quantity만큼 증가시키고 event_handled에 기록한다.")
        @Test
        void incrementsSalesCountPerItem_whenNewEventId() {
            // arrange
            String eventId = "order-events:0:10";
            ProductMetricsModel metrics1 = new ProductMetricsModel(1L);
            ProductMetricsModel metrics2 = new ProductMetricsModel(2L);
            when(eventHandledRepository.existsByEventId(eventId)).thenReturn(false);
            when(productMetricsRepository.findByProductId(1L)).thenReturn(Optional.of(metrics1));
            when(productMetricsRepository.findByProductId(2L)).thenReturn(Optional.of(metrics2));

            // act
            salesAggregatorService.handleOrderConfirmed(eventId, List.of(
                new SalesItem(1L, 2L),
                new SalesItem(2L, 1L)
            ));

            // assert
            assertThat(metrics1.getSalesCount()).isEqualTo(2L);
            assertThat(metrics2.getSalesCount()).isEqualTo(1L);
            verify(eventHandledRepository).save(any(EventHandledModel.class));
            verify(productMetricsRepository).save(metrics1);
            verify(productMetricsRepository).save(metrics2);
        }

        @DisplayName("이미 처리된 eventId면, 아무 집계도 하지 않고 중복 기록도 남기지 않는다.")
        @Test
        void skips_whenEventIdAlreadyHandled() {
            // arrange
            String eventId = "order-events:0:10";
            when(eventHandledRepository.existsByEventId(eventId)).thenReturn(true);

            // act
            salesAggregatorService.handleOrderConfirmed(eventId, List.of(new SalesItem(1L, 2L)));

            // assert
            verify(eventHandledRepository, never()).save(any());
            verify(productMetricsRepository, never()).findByProductId(any());
            verify(productMetricsRepository, never()).save(any());
        }

        @DisplayName("product_metrics가 없는 productId면, 새로 생성한 뒤 sales_count를 증가시킨다.")
        @Test
        void createsMetrics_whenProductMetricsNotExists() {
            // arrange
            String eventId = "order-events:0:10";
            when(eventHandledRepository.existsByEventId(eventId)).thenReturn(false);
            when(productMetricsRepository.findByProductId(99L)).thenReturn(Optional.empty());

            // act
            salesAggregatorService.handleOrderConfirmed(eventId, List.of(new SalesItem(99L, 3L)));

            // assert
            ArgumentCaptor<ProductMetricsModel> captor = ArgumentCaptor.forClass(ProductMetricsModel.class);
            verify(productMetricsRepository).save(captor.capture());
            ProductMetricsModel saved = captor.getValue();
            assertThat(saved.getProductId()).isEqualTo(99L);
            assertThat(saved.getSalesCount()).isEqualTo(3L);
        }

        @DisplayName("같은 productId가 items에 중복되면, 누적해서 sales_count를 증가시킨다.")
        @Test
        void accumulates_whenSameProductIdRepeatedInItems() {
            // arrange
            String eventId = "order-events:0:10";
            Map<Long, ProductMetricsModel> store = new HashMap<>();
            ProductMetricsModel metrics = new ProductMetricsModel(1L);
            store.put(1L, metrics);
            when(eventHandledRepository.existsByEventId(eventId)).thenReturn(false);
            when(productMetricsRepository.findByProductId(1L)).thenAnswer(inv -> Optional.of(store.get(1L)));

            // act
            salesAggregatorService.handleOrderConfirmed(eventId, List.of(
                new SalesItem(1L, 2L),
                new SalesItem(1L, 3L)
            ));

            // assert
            assertThat(metrics.getSalesCount()).isEqualTo(5L);
        }
    }
}
