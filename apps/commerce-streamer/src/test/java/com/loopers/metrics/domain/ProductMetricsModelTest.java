package com.loopers.metrics.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductMetricsModelTest {

    @DisplayName("applyView를 호출할 때,")
    @Nested
    class ApplyView {

        @DisplayName("최초 조회면, viewCount가 1이 되고 lastViewEventAt이 기록된다.")
        @Test
        void incrementsAndRecords_whenFirstView() {
            // arrange
            ProductMetricsModel metrics = new ProductMetricsModel(1L);

            // act
            metrics.applyView(1000L);

            // assert
            assertThat(metrics.getViewCount()).isEqualTo(1L);
            assertThat(metrics.getLastViewEventAtMillis()).isEqualTo(1000L);
        }

        @DisplayName("더 최신 occurredAt이면, viewCount가 증가하고 lastViewEventAt이 갱신된다.")
        @Test
        void incrementsAndUpdates_whenNewerOccurredAt() {
            // arrange
            ProductMetricsModel metrics = new ProductMetricsModel(1L);
            metrics.applyView(1000L);

            // act
            metrics.applyView(2000L);

            // assert
            assertThat(metrics.getViewCount()).isEqualTo(2L);
            assertThat(metrics.getLastViewEventAtMillis()).isEqualTo(2000L);
        }

        @DisplayName("더 오래된(stale) occurredAt이면, 아무것도 반영하지 않는다.")
        @Test
        void skips_whenStaleOccurredAt() {
            // arrange
            ProductMetricsModel metrics = new ProductMetricsModel(1L);
            metrics.applyView(2000L);

            // act
            metrics.applyView(1000L);

            // assert
            assertThat(metrics.getViewCount()).isEqualTo(1L);
            assertThat(metrics.getLastViewEventAtMillis()).isEqualTo(2000L);
        }
    }
}
