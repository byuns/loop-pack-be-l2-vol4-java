package com.loopers.ranking.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RankingScorePolicyTest {

    private final RankingScorePolicy policy = new RankingScorePolicy();

    @DisplayName("이벤트별 점수를 계산할 때,")
    @Nested
    class Score {

        @DisplayName("조회 점수는 0.1이다.")
        @Test
        void viewScoreIs0_1() {
            assertThat(policy.viewScore()).isEqualTo(0.1);
        }

        @DisplayName("좋아요 추가 점수는 0.2, 취소 점수는 -0.2이다.")
        @Test
        void likeScores() {
            assertThat(policy.likeAddedScore()).isEqualTo(0.2);
            assertThat(policy.likeCancelledScore()).isEqualTo(-0.2);
        }

        @DisplayName("주문 점수는 0.6 × log10(1 + price×quantity)이다.")
        @Test
        void orderScoreIsLogNormalized() {
            // arrange
            long price = 100000L;
            long quantity = 2L;
            double expected = 0.6 * Math.log10(1 + (double) (price * quantity));

            // act
            double actual = policy.orderScore(price, quantity);

            // assert
            assertThat(actual).isCloseTo(expected, within(1e-9));
        }

        @DisplayName("주문 금액이 0이면(price 또는 quantity가 0), log(1)=0이라 0점이다.")
        @Test
        void orderScoreIsZero_whenAmountIsZero() {
            assertThat(policy.orderScore(0L, 5L)).isEqualTo(0.0);
        }
    }
}
