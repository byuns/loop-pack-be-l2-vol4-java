package com.loopers.queue.interfaces;

import com.loopers.queue.application.WaitingInfo;
import com.loopers.queue.domain.QueueStatus;

public class QueueV1Dto {

    public record EnterRequest(Long userId) {}

    public record WaitingResponse(
        QueueStatus status,
        Long position,
        Long estimatedWaitTime,
        String token
    ) {
        public static WaitingResponse from(WaitingInfo info) {
            return new WaitingResponse(
                info.status(),
                info.position(),
                info.estimatedWaitTime(),
                info.token()
            );
        }
    }

    public record SizeResponse(Long size) {
        public static SizeResponse of(Long size) {
            return new SizeResponse(size);
        }
    }
}
