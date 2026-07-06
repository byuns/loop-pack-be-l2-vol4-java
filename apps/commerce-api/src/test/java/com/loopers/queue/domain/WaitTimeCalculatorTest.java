package com.loopers.queue.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class WaitTimeCalculatorTest {

    // batch-size 10, interval 100ms → 초당 100명
    private final WaitTimeCalculator calculator = new WaitTimeCalculator(10, 100);

    @DisplayName("예상 대기 시간을 계산할 때, ")
    @Nested
    class EstimateWaitSeconds {

        @DisplayName("342번째 유저면, 올림 처리되어 4초를 반환한다.")
        @Test
        void returnsFourSeconds_whenPositionIs342() {
            // act
            long seconds = calculator.estimateWaitSeconds(342L);

            // assert
            assertThat(seconds).isEqualTo(4L);
        }

        @DisplayName("5000번째 유저면, 50초를 반환한다.")
        @Test
        void returnsFiftySeconds_whenPositionIs5000() {
            // act
            long seconds = calculator.estimateWaitSeconds(5000L);

            // assert
            assertThat(seconds).isEqualTo(50L);
        }

        @DisplayName("다음 배치 입장 예정자(position ≤ 배치 크기)면, 0초를 반환한다.")
        @Test
        void returnsZero_whenPositionIsWithinNextBatch() {
            // act & assert
            assertAll(
                () -> assertThat(calculator.estimateWaitSeconds(1L)).isEqualTo(0L),
                () -> assertThat(calculator.estimateWaitSeconds(10L)).isEqualTo(0L)
            );
        }
    }

    @DisplayName("다음 조회 권장 간격을 계산할 때, ")
    @Nested
    class PollAfterSeconds {

        @DisplayName("예상 대기 시간이 60초 이상이면, 10초를 반환한다.")
        @Test
        void returnsTen_whenEstimateIsSixtySecondsOrMore() {
            // act & assert
            assertAll(
                () -> assertThat(calculator.pollAfterSeconds(60L)).isEqualTo(10L),
                () -> assertThat(calculator.pollAfterSeconds(120L)).isEqualTo(10L)
            );
        }

        @DisplayName("예상 대기 시간이 10초 이상 60초 미만이면, 5초를 반환한다.")
        @Test
        void returnsFive_whenEstimateIsBetweenTenAndSixtySeconds() {
            // act & assert
            assertAll(
                () -> assertThat(calculator.pollAfterSeconds(10L)).isEqualTo(5L),
                () -> assertThat(calculator.pollAfterSeconds(59L)).isEqualTo(5L)
            );
        }

        @DisplayName("예상 대기 시간이 10초 미만이면, 2초를 반환한다.")
        @Test
        void returnsTwo_whenEstimateIsLessThanTenSeconds() {
            // act & assert
            assertAll(
                () -> assertThat(calculator.pollAfterSeconds(9L)).isEqualTo(2L),
                () -> assertThat(calculator.pollAfterSeconds(0L)).isEqualTo(2L)
            );
        }
    }
}
