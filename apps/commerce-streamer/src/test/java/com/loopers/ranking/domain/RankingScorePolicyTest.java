package com.loopers.ranking.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RankingScorePolicyTest {

    // 기본 가중치(0.1/0.2/0.6)를 설정에서 주입받는 구조
    private final RankingScorePolicy policy =
        new RankingScorePolicy(new RankingWeightProperties(0.1, 0.2, 0.6));

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

        @DisplayName("가중치가 순서에 반영된다 — 주문 1건(1만원)이 좋아요 3건보다 점수가 높다.")
        @Test
        void orderOutweighsThreeLikes() {
            // arrange
            double orderScore = policy.orderScore(10000L, 1L);
            double threeLikesScore = policy.likeAddedScore() * 3;

            // act & assert
            assertThat(orderScore).isGreaterThan(threeLikesScore);
        }
    }

    @DisplayName("설정으로 주입한 가중치가,")
    @Nested
    class ConfiguredWeight {

        // 재배포 없이 가중치를 바꾸는 시나리오 — 설정값이 그대로 점수에 반영돼야 한다
        private final RankingScorePolicy custom =
            new RankingScorePolicy(new RankingWeightProperties(0.15, 0.25, 0.5));

        @DisplayName("조회·좋아요 점수에 그대로 반영된다.")
        @Test
        void reflectsViewAndLikeWeights() {
            assertThat(custom.viewScore()).isEqualTo(0.15);
            assertThat(custom.likeAddedScore()).isEqualTo(0.25);
            assertThat(custom.likeCancelledScore()).isEqualTo(-0.25);
        }

        @DisplayName("주문 가중치 × log10(1+금액)으로 반영된다.")
        @Test
        void reflectsOrderWeight() {
            // arrange
            double expected = 0.5 * Math.log10(1 + 10000.0);

            // act & assert
            assertThat(custom.orderScore(10000L, 1L)).isCloseTo(expected, within(1e-9));
        }
    }
}
