package com.loopers.ranking.application;

import com.loopers.ranking.domain.RankingKey;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 실제 Redis로 Carry-Over 전 구간 검증 — 오늘 키 Top-N을 감쇠해 내일 키에 적재하고 TTL을 건다.
 */
@SpringBootTest
class RankingCarryOverIntegrationTest {

    private static final String TODAY_KEY = RankingKey.daily(LocalDate.now());
    private static final String TOMORROW_KEY = RankingKey.daily(LocalDate.now().plusDays(1));

    // 브로커 없이 검증 — Kafka 리스너가 뜨지 않게 한다
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        // 테스트 결정성을 위해 decay·topN 고정
        registry.add("ranking.carryover.decay", () -> "0.1");
        registry.add("ranking.carryover.top-n", () -> "2");
    }

    @Autowired
    private RankingCarryOverService carryOverService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    @DisplayName("오늘 키에 점수가 쌓여 있으면, Top-N만 감쇠(×0.1)해 내일 키에 적재하고 TTL을 건다.")
    @Test
    void carriesOverDecayedTopN_withTtl() {
        // arrange — 오늘 키에 3건(1위 30, 2위 20, 3위 10). topN=2라 상위 2건만 넘어가야 한다.
        redisTemplate.opsForZSet().add(TODAY_KEY, "1", 30.0);
        redisTemplate.opsForZSet().add(TODAY_KEY, "2", 20.0);
        redisTemplate.opsForZSet().add(TODAY_KEY, "3", 10.0);

        // act
        carryOverService.carryOver();

        // assert — 내일 키: 1→3.0, 2→2.0 존재, 3은 없음(Top-2 밖), TTL 걸림
        Double s1 = redisTemplate.opsForZSet().score(TOMORROW_KEY, "1");
        Double s2 = redisTemplate.opsForZSet().score(TOMORROW_KEY, "2");
        Double s3 = redisTemplate.opsForZSet().score(TOMORROW_KEY, "3");
        Long ttl = redisTemplate.getExpire(TOMORROW_KEY);
        assertThat(s1).isCloseTo(3.0, within(1e-9));
        assertThat(s2).isCloseTo(2.0, within(1e-9));
        assertThat(s3).isNull();
        assertThat(ttl).isGreaterThan(0);
    }

    @DisplayName("오늘 키가 비어 있으면, 내일 키를 만들지 않는다.")
    @Test
    void doesNothing_whenTodayIsEmpty() {
        // act
        carryOverService.carryOver();

        // assert
        assertThat(redisTemplate.hasKey(TOMORROW_KEY)).isFalse();
    }
}
