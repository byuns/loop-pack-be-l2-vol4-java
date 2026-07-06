package com.loopers.queue.infrastructure;

import com.loopers.queue.domain.EntryTokenModel;
import com.loopers.queue.domain.EntryTokenRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

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

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
