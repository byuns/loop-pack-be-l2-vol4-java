package com.loopers.ranking.infrastructure;

import com.loopers.ranking.domain.RankedEntry;
import com.loopers.ranking.domain.RankingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
@Component
public class RankingRedisRepositoryImpl implements RankingRepository {

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public List<RankedEntry> findPage(String key, int offset, int limit) {
        Set<TypedTuple<String>> tuples =
            redisTemplate.opsForZSet().reverseRangeWithScores(key, offset, (long) offset + limit - 1);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        return tuples.stream()
            .map(t -> new RankedEntry(Long.valueOf(t.getValue()), t.getScore() == null ? 0.0 : t.getScore()))
            .toList();
    }

    @Override
    public Long findRank(String key, Long productId) {
        Long rank = redisTemplate.opsForZSet().reverseRank(key, String.valueOf(productId));
        // ZREVRANK는 0-based → 1위=1로 변환. 순위권에 없으면 null.
        return rank == null ? null : rank + 1;
    }
}
