package com.loopers.queue.infrastructure;

import com.loopers.queue.domain.EntryTokenModel;
import com.loopers.queue.domain.EntryTokenRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Repository
public class EntryTokenRepositoryImpl implements EntryTokenRepository {

    static final String KEY_PREFIX = "queue:token:";
    // visibleAt(millis)와 token(UUID)을 하나의 String value에 담아 저장 — 단일 GET/SET으로 처리
    private static final String DELIMITER = "|";

    private final RedisTemplate<String, String> redisTemplate;

    public EntryTokenRepositoryImpl(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void save(EntryTokenModel entryToken, Duration ttl) {
        String value = entryToken.getVisibleAt().toEpochMilli() + DELIMITER + entryToken.getToken();
        redisTemplate.opsForValue().set(key(entryToken.getUserId()), value, ttl);
    }

    @Override
    public Optional<EntryTokenModel> findByUserId(Long userId) {
        String value = redisTemplate.opsForValue().get(key(userId));
        if (value == null) {
            return Optional.empty();
        }
        int idx = value.indexOf(DELIMITER);
        if (idx < 0) {
            return Optional.empty();
        }
        long visibleAtMillis = Long.parseLong(value.substring(0, idx));
        String token = value.substring(idx + 1);
        return Optional.of(new EntryTokenModel(userId, token, Instant.ofEpochMilli(visibleAtMillis)));
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
