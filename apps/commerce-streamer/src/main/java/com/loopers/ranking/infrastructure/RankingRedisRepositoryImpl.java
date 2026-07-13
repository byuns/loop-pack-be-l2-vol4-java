package com.loopers.ranking.infrastructure;

import com.loopers.ranking.domain.RankingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@RequiredArgsConstructor
@Component
public class RankingRedisRepositoryImpl implements RankingRepository {

    // 랭킹 키 TTL 2일 — 매 반영마다 갱신한다(설계: EXPIRE를 항상 다시 걸어 자가 치유)
    private static final Duration TTL = Duration.ofDays(2);

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public void incrementScore(String key, Long productId, double score) {
        redisTemplate.opsForZSet().incrementScore(key, String.valueOf(productId), score);
        redisTemplate.expire(key, TTL);
    }
}
