package com.loopers.queue.application;

import com.loopers.queue.domain.EntryTokenModel;
import com.loopers.queue.domain.EntryTokenRepository;
import com.loopers.queue.domain.QueueRepository;
import com.loopers.queue.domain.WaitingModel;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Component
public class QueueFacade {

    private final QueueRepository queueRepository;
    private final EntryTokenRepository entryTokenRepository;
    private final long maxQueueSize;
    private final int batchSize;
    private final Duration tokenTtl;

    public QueueFacade(
        QueueRepository queueRepository,
        EntryTokenRepository entryTokenRepository,
        @Value("${queue.max-size:100000}") long maxQueueSize,
        @Value("${queue.scheduler.batch-size:10}") int batchSize,
        @Value("${queue.token.ttl-seconds:300}") long tokenTtlSeconds
    ) {
        this.queueRepository = queueRepository;
        this.entryTokenRepository = entryTokenRepository;
        this.maxQueueSize = maxQueueSize;
        this.batchSize = batchSize;
        this.tokenTtl = Duration.ofSeconds(tokenTtlSeconds);
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
        // 스케줄러가 꺼내간 유저는 ZRANK가 null이므로, 토큰을 먼저 확인해야 READY와 NOT_IN_QUEUE를 구분할 수 있다
        Optional<String> token = entryTokenRepository.findByUserId(userId);
        if (token.isPresent()) {
            return WaitingInfo.ready(token.get());
        }
        Optional<Long> position = queueRepository.getPosition(userId);
        if (position.isEmpty()) {
            return WaitingInfo.notInQueue();
        }
        WaitingModel model = new WaitingModel(userId, position.get());
        return WaitingInfo.waiting(model, null);
    }

    /**
     * 대기열 앞에서 배치 크기만큼 꺼내 입장 토큰을 발급한다. 스케줄러가 주기적으로 호출한다.
     */
    public void admitNextBatch() {
        List<Long> userIds = queueRepository.popMin(batchSize);
        for (Long userId : userIds) {
            entryTokenRepository.save(EntryTokenModel.issue(userId), tokenTtl);
        }
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
