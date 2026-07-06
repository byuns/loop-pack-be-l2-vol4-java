package com.loopers.queue.application;

import com.loopers.queue.domain.QueueStatus;
import com.loopers.queue.domain.WaitingModel;

public record WaitingInfo(
    QueueStatus status,
    Long position,
    Long estimatedWaitTime,
    String token
) {
    public static WaitingInfo waiting(WaitingModel model, Long estimatedWaitTime) {
        return new WaitingInfo(QueueStatus.WAITING, model.getPosition(), estimatedWaitTime, null);
    }

    public static WaitingInfo notInQueue() {
        return new WaitingInfo(QueueStatus.NOT_IN_QUEUE, null, null, null);
    }

    public static WaitingInfo ready(String token) {
        return new WaitingInfo(QueueStatus.READY, null, null, token);
    }
}
