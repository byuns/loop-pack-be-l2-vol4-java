package com.loopers.ranking.application;

import com.loopers.like.application.LikeAggregatorService;
import com.loopers.payment.application.SalesAggregatorService;
import com.loopers.payment.application.SalesItem;
import com.loopers.ranking.domain.RankingKey;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import com.loopers.view.application.ViewAggregatorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 이벤트 발행 → (MySQL 커밋 후 AFTER_COMMIT) → Redis ZSET 점수 반영 구간을 검증한다.
 * Aggregator를 직접 호출해 실제 @Transactional 커밋 후 RankingEventListener가 ZSET에 반영하는지 본다.
 * (ZSET → API 조회 구간은 commerce-api RankingV1ApiE2ETest가 담당)
 */
@SpringBootTest
class RankingScoreIntegrationTest {

    private static final String KEY = RankingKey.daily(LocalDate.now());

    // 브로커 없이 Aggregator 직접 호출로 검증 — Kafka 리스너가 뜨지 않게 한다
    @DynamicPropertySource
    static void disableKafkaListener(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired
    private ViewAggregatorService viewAggregatorService;

    @Autowired
    private LikeAggregatorService likeAggregatorService;

    @Autowired
    private SalesAggregatorService salesAggregatorService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private Double score(long productId) {
        return redisTemplate.opsForZSet().score(KEY, String.valueOf(productId));
    }

    // 현재/직전 분 버킷 중 리스너가 기록한 쪽의 점수(분 경계 flake 방지)
    private double minuteBucketScore(long productId) {
        LocalDateTime now = LocalDateTime.now();
        double sum = 0.0;
        for (LocalDateTime m : List.of(now, now.minusMinutes(1))) {
            Double s = redisTemplate.opsForZSet().score(RankingKey.minute(m), String.valueOf(productId));
            if (s != null) {
                sum += s;
            }
        }
        return sum;
    }

    @DisplayName("이벤트를 처리하면,")
    @Nested
    class OnEvent {

        @DisplayName("조회 이벤트가 커밋되면, 오늘 일간 ZSET에 상품 점수가 0.1 반영된다.")
        @Test
        void reflectsViewScore_whenProductViewed() {
            // arrange
            long productId = 1L;

            // act
            viewAggregatorService.handleProductViewed("view:1", productId, 1000L);

            // assert
            assertThat(score(productId)).isCloseTo(0.1, within(1e-9));
        }

        @DisplayName("조회 이벤트가 커밋되면, 일간 키뿐 아니라 현재 분 버킷에도 점수가 반영되고 TTL이 걸린다.")
        @Test
        void reflectsMinuteBucketScore_whenProductViewed() {
            // arrange
            long productId = 10L;

            // act
            viewAggregatorService.handleProductViewed("view:min", productId, 1000L);

            // assert — 분 버킷에도 0.1 반영, 해당 버킷에 TTL 존재
            assertThat(minuteBucketScore(productId)).isCloseTo(0.1, within(1e-9));
            Long ttlNow = redisTemplate.getExpire(RankingKey.minute(LocalDateTime.now()));
            Long ttlPrev = redisTemplate.getExpire(RankingKey.minute(LocalDateTime.now().minusMinutes(1)));
            assertThat(Math.max(ttlNow, ttlPrev)).isGreaterThan(0);
        }

        @DisplayName("주문 이벤트가 커밋되면, items별 상품 점수가 0.6×log10(1+price×qty)로 반영된다.")
        @Test
        void reflectsOrderScore_whenOrderConfirmed() {
            // arrange
            long productId = 2L;
            long price = 10000L;
            long quantity = 2L;
            double expected = 0.6 * Math.log10(1 + (double) (price * quantity));

            // act
            salesAggregatorService.handleOrderConfirmed(
                "order:1", List.of(new SalesItem(productId, quantity, price)));

            // assert
            assertThat(score(productId)).isCloseTo(expected, within(1e-9));
        }
    }

    @DisplayName("멱등성 — 같은 eventId가 두 번 처리돼도,")
    @Nested
    class Idempotency {

        @DisplayName("event_handled 게이트가 두 번째를 skip해 ZSET 점수가 중복 가산되지 않는다.")
        @Test
        void doesNotDoubleCount_whenSameEventIdProcessedTwice() {
            // arrange
            long productId = 3L;

            // act — 동일 eventId로 두 번 처리
            viewAggregatorService.handleProductViewed("view:dup", productId, 1000L);
            viewAggregatorService.handleProductViewed("view:dup", productId, 1000L);

            // assert — 0.1 한 번만 반영(0.2가 아님)
            assertThat(score(productId)).isCloseTo(0.1, within(1e-9));
        }
    }
}
