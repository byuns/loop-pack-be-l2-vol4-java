package com.loopers.ranking.infrastructure;

import com.loopers.ranking.domain.RankedEntry;
import com.loopers.ranking.domain.RankingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
@Component
public class RankingRedisRepositoryImpl implements RankingRepository {

    // 랭킹 키 TTL 2일 — 매 반영마다 갱신한다(설계: EXPIRE를 항상 다시 걸어 자가 치유)
    private static final Duration TTL = Duration.ofDays(2);

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public void incrementScore(String key, Long productId, double score) {
        incrementScore(key, productId, score, TTL);
    }

    @Override
    public void incrementScore(String key, Long productId, double score, Duration ttl) {
        redisTemplate.opsForZSet().incrementScore(key, String.valueOf(productId), score);
        redisTemplate.expire(key, ttl);
    }

    @Override
    public void unionInto(String destKey, List<String> sourceKeys, Duration ttl) {
        if (sourceKeys.isEmpty()) {
            return;
        }
        // ZUNIONSTORE dest sourceKeys... — 없는 키는 빈 집합으로 취급된다.
        String first = sourceKeys.get(0);
        List<String> rest = sourceKeys.subList(1, sourceKeys.size());
        redisTemplate.opsForZSet().unionAndStore(first, rest, destKey);
        redisTemplate.expire(destKey, ttl);
    }

    @Override
    public List<RankedEntry> findTopScores(String key, int limit) {
        Set<TypedTuple<String>> tuples =
            redisTemplate.opsForZSet().reverseRangeWithScores(key, 0, (long) limit - 1);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        return tuples.stream()
            .map(t -> new RankedEntry(Long.valueOf(t.getValue()), t.getScore() == null ? 0.0 : t.getScore()))
            .toList();
    }

    @Override
    public void seedScores(String key, List<RankedEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        for (RankedEntry entry : entries) {
            // ZADD 절대 세팅 — 재실행돼도 결과 동일(멱등)
            redisTemplate.opsForZSet().add(key, String.valueOf(entry.productId()), entry.score());
        }
        redisTemplate.expire(key, TTL);
    }
}
