package com.loopers.ranking.application;

import com.loopers.ranking.domain.RankingKey;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 실제 Redis로 시간 랭킹 사전 합산 전 구간 검증 — 최근 60개 분 버킷을 union해 롤링 키에 저장하고 TTL을 건다.
 */
@SpringBootTest
class RankingHourlyAggregatorIntegrationTest {

    private static final String CURRENT_KEY = RankingKey.hourlyCurrent();

    // 브로커 없이 검증 — Kafka 리스너가 뜨지 않게 한다
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("ranking.hourly.window-minutes", () -> "60");
    }

    @Autowired
    private RankingHourlyAggregator aggregator;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private RedisCleanUp redisCleanUp;

    // [fix] 백그라운드 사전 합산 스케줄러가 상시 도는 환경에서 시작 시점 잔여를 없애 결정성 확보
    @BeforeEach
    void setUp() {
        redisCleanUp.truncateAll();
    }

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    private void addToBucket(LocalDateTime minute, String productId, double score) {
        redisTemplate.opsForZSet().add(RankingKey.minute(minute), productId, score);
    }

    @DisplayName("여러 분 버킷에 흩어진 점수를 합산해 hourly:current에 모으고 TTL을 건다.")
    @Test
    void unionsScatteredBuckets_withTtl() {
        // arrange — 윈도우 안(현재분, 5분 전)에 같은 상품 점수를 나눠 적재
        LocalDateTime now = LocalDateTime.now();
        addToBucket(now, "1", 3.0);
        addToBucket(now.minusMinutes(5), "1", 2.0);
        addToBucket(now.minusMinutes(5), "2", 4.0);

        // act
        aggregator.refresh();

        // assert — 1은 3+2=5, 2는 4, TTL 걸림
        Double s1 = redisTemplate.opsForZSet().score(CURRENT_KEY, "1");
        Double s2 = redisTemplate.opsForZSet().score(CURRENT_KEY, "2");
        Long ttl = redisTemplate.getExpire(CURRENT_KEY);
        assertThat(s1).isCloseTo(5.0, within(1e-9));
        assertThat(s2).isCloseTo(4.0, within(1e-9));
        assertThat(ttl).isGreaterThan(0);
    }

    @DisplayName("윈도우 밖(60분 초과) 버킷 점수는 합산에 포함되지 않는다.")
    @Test
    void excludesBucketsOutsideWindow() {
        // arrange — 윈도우 안(현재분)과 밖(61분 전)
        LocalDateTime now = LocalDateTime.now();
        addToBucket(now, "1", 1.0);
        addToBucket(now.minusMinutes(61), "1", 100.0);

        // act
        aggregator.refresh();

        // assert — 61분 전 점수는 제외되어 1.0만 반영
        Double s1 = redisTemplate.opsForZSet().score(CURRENT_KEY, "1");
        assertThat(s1).isCloseTo(1.0, within(1e-9));
    }

    @DisplayName("모든 분 버킷이 비어 있으면, hourly:current를 만들지 않는다.")
    @Test
    void doesNothing_whenAllBucketsEmpty() {
        // act
        aggregator.refresh();

        // assert
        assertThat(redisTemplate.hasKey(CURRENT_KEY)).isFalse();
    }
}
