package com.loopers.queue.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class EntryTokenModel {

    private final Long userId;
    private final String token;
    private final Instant visibleAt;

    public EntryTokenModel(Long userId, String token, Instant visibleAt) {
        if (userId == null || userId <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "userId는 양수여야 합니다.");
        }
        if (token == null || token.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "token은 비어있을 수 없습니다.");
        }
        if (visibleAt == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "visibleAt은 비어있을 수 없습니다.");
        }
        this.userId = userId;
        this.token = token;
        this.visibleAt = visibleAt;
    }

    // 토큰 발급 후 [0, jitterMs] 범위의 랜덤 지연을 부여해 노출 시점을 흩뿌린다 (Thundering Herd 완화)
    public static EntryTokenModel issue(Long userId, long jitterMs) {
        long delay = jitterMs <= 0 ? 0L : ThreadLocalRandom.current().nextLong(jitterMs + 1);
        return new EntryTokenModel(userId, UUID.randomUUID().toString(), Instant.now().plusMillis(delay));
    }

    public boolean isVisible(Instant now) {
        return !visibleAt.isAfter(now);
    }

    public Long getUserId() {
        return userId;
    }

    public String getToken() {
        return token;
    }

    public Instant getVisibleAt() {
        return visibleAt;
    }
}
