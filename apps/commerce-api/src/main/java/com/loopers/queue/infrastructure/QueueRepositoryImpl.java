package com.loopers.queue.infrastructure;

import com.loopers.queue.domain.QueueRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class QueueRepositoryImpl implements QueueRepository {

    static final String QUEUE_KEY = "queue:waiting";
    static final String COUNTER_KEY = "queue:counter";
    private static final long QUEUE_FULL_MARKER = -1L;

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<Long> enterScript;

    public QueueRepositoryImpl(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.enterScript = new DefaultRedisScript<>(
            // KEYS[1] = queue key, KEYS[2] = counter key
            // ARGV[1] = userId, ARGV[2] = maxSize
            // 이미 있으면 기존 순번 반환, 상한 초과면 -1, 그 외엔 INCR+ZADD 후 새 순번 반환
            "if redis.call('ZSCORE', KEYS[1], ARGV[1]) then\n" +
            "  return redis.call('ZRANK', KEYS[1], ARGV[1]) + 1\n" +
            "end\n" +
            "if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then\n" +
            "  return -1\n" +
            "end\n" +
            "local score = redis.call('INCR', KEYS[2])\n" +
            "redis.call('ZADD', KEYS[1], score, ARGV[1])\n" +
            "return redis.call('ZRANK', KEYS[1], ARGV[1]) + 1",
            Long.class
        );
    }

    @Override
    public Optional<Long> enter(Long userId, long maxSize) {
        Long result = redisTemplate.execute(
            enterScript,
            List.of(QUEUE_KEY, COUNTER_KEY),
            String.valueOf(userId),
            String.valueOf(maxSize)
        );
        if (result == null || result == QUEUE_FULL_MARKER) {
            return Optional.empty();
        }
        return Optional.of(result);
    }

    @Override
    public Optional<Long> getPosition(Long userId) {
        Long rank = redisTemplate.opsForZSet().rank(QUEUE_KEY, String.valueOf(userId));
        if (rank == null) {
            return Optional.empty();
        }
        return Optional.of(rank + 1);
    }

    @Override
    public Long getSize() {
        Long size = redisTemplate.opsForZSet().zCard(QUEUE_KEY);
        return size == null ? 0L : size;
    }
}
