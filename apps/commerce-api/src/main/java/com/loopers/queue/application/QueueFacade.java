package com.loopers.queue.application;

import com.loopers.queue.domain.QueueRepository;
import com.loopers.queue.domain.WaitingModel;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class QueueFacade {

    private final QueueRepository queueRepository;
    private final long maxQueueSize;

    public QueueFacade(
        QueueRepository queueRepository,
        @Value("${queue.max-size:100000}") long maxQueueSize
    ) {
        this.queueRepository = queueRepository;
        this.maxQueueSize = maxQueueSize;
    }

    public WaitingInfo enter(Long userId) {
        validateUserId(userId);
        Long position = queueRepository.enter(userId, maxQueueSize)
            .orElseThrow(() -> new CoreException(ErrorType.TOO_MANY_REQUESTS, "대기열이 가득 찼습니다."));
        WaitingModel model = new WaitingModel(userId, position);
        return WaitingInfo.waiting(model, null);
    }

    public WaitingInfo getPosition(Long userId) {
        validateUserId(userId);
        Optional<Long> position = queueRepository.getPosition(userId);
        if (position.isEmpty()) {
            return WaitingInfo.notInQueue();
        }
        WaitingModel model = new WaitingModel(userId, position.get());
        return WaitingInfo.waiting(model, null);
    }

    private void validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "userId는 양수여야 합니다.");
        }
    }

    public Long getSize() {
        return queueRepository.getSize();
    }
}
