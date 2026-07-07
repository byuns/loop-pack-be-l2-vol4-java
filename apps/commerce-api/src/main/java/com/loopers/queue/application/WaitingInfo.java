package com.loopers.queue.application;

import com.loopers.queue.domain.QueueStatus;
import com.loopers.queue.domain.WaitingModel;

import java.time.ZonedDateTime;

public record WaitingInfo(
    QueueStatus status,
    Long position,
    Long estimatedWaitTime,
    Long pollAfter,
    String token,
    ZonedDateTime expiresAt
) {
    public static WaitingInfo waiting(WaitingModel model, Long estimatedWaitTime, Long pollAfter) {
        return new WaitingInfo(QueueStatus.WAITING, model.getPosition(), estimatedWaitTime, pollAfter, null, null);
    }

    // 토큰은 발급됐지만 Jitter visibleAt 이전 — position=0으로 "곧 입장" 상태를 표현
    public static WaitingInfo pendingVisibility(long remainingSeconds) {
        return new WaitingInfo(QueueStatus.WAITING, 0L, remainingSeconds, 1L, null, null);
    }

    public static WaitingInfo notInQueue() {
        return new WaitingInfo(QueueStatus.NOT_IN_QUEUE, null, null, null, null, null);
    }

    public static WaitingInfo ready(String token, ZonedDateTime expiresAt) {
        return new WaitingInfo(QueueStatus.READY, null, null, null, token, expiresAt);
    }
}
