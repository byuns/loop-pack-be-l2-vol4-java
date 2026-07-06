package com.loopers.queue.infrastructure;

import com.loopers.queue.domain.EntryTokenModel;
import com.loopers.queue.domain.EntryTokenRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Repository
public class EntryTokenRepositoryImpl implements EntryTokenRepository {

    static final String KEY_PREFIX = "queue:token:";

    private final RedisTemplate<String, String> redisTemplate;

    public EntryTokenRepositoryImpl(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void save(EntryTokenModel entryToken, Duration ttl) {
        redisTemplate.opsForValue().set(key(entryToken.getUserId()), entryToken.getToken(), ttl);
    }

    @Override
    public Optional<String> findByUserId(Long userId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key(userId)));
    }

    @Override
    public void deleteByUserId(Long userId) {
        redisTemplate.delete(key(userId));
    }

    @Override
    public Optional<Duration> getTtl(Long userId) {
        Long seconds = redisTemplate.getExpire(key(userId), TimeUnit.SECONDS);
        // -2: 키 없음, -1: TTL 미설정 — 둘 다 유효한 토큰 TTL이 아님
        if (seconds == null || seconds < 0) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofSeconds(seconds));
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
