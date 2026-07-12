package com.loopers.queue.application;

import com.loopers.queue.domain.EntryTokenModel;
import com.loopers.queue.domain.EntryTokenRepository;
import com.loopers.queue.domain.QueueRepository;
import com.loopers.queue.domain.WaitTimeCalculator;
import com.loopers.queue.domain.WaitingModel;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@Component
public class QueueFacade {

    private final QueueRepository queueRepository;
    private final EntryTokenRepository entryTokenRepository;
    private final SsePublisher ssePublisher;
    private final WaitTimeCalculator waitTimeCalculator;
    private final long maxQueueSize;
    private final int batchSize;
    private final Duration tokenTtl;
    private final long jitterMaxMs;

    public QueueFacade(
        QueueRepository queueRepository,
        EntryTokenRepository entryTokenRepository,
        SsePublisher ssePublisher,
        @Value("${queue.max-size:100000}") long maxQueueSize,
        @Value("${queue.scheduler.batch-size:10}") int batchSize,
        @Value("${queue.scheduler.interval-ms:100}") long intervalMs,
        @Value("${queue.token.ttl-seconds:300}") long tokenTtlSeconds,
        @Value("${queue.jitter.max-ms:0}") long jitterMaxMs
    ) {
        this.queueRepository = queueRepository;
        this.entryTokenRepository = entryTokenRepository;
        this.ssePublisher = ssePublisher;
        this.waitTimeCalculator = new WaitTimeCalculator(batchSize, intervalMs);
        this.maxQueueSize = maxQueueSize;
        this.batchSize = batchSize;
        this.tokenTtl = Duration.ofSeconds(tokenTtlSeconds);
        this.jitterMaxMs = jitterMaxMs;
    }

    public WaitingInfo enter(Long userId) {
        validateUserId(userId);
        Long position = queueRepository.enter(userId, maxQueueSize)
            .orElseThrow(() -> new CoreException(ErrorType.TOO_MANY_REQUESTS, "대기열이 가득 찼습니다."));
        return waitingInfoOf(new WaitingModel(userId, position));
    }

    public WaitingInfo getPosition(Long userId) {
        validateUserId(userId);
        // 스케줄러가 꺼내간 유저는 ZRANK가 null이므로, 토큰을 먼저 확인해야 READY와 NOT_IN_QUEUE를 구분할 수 있다
        Optional<EntryTokenModel> tokenModel = entryTokenRepository.findByUserId(userId);
        if (tokenModel.isPresent()) {
            EntryTokenModel model = tokenModel.get();
            Instant now = Instant.now();
            if (model.isVisible(now)) {
                // 조회와 TTL 확인 사이에 토큰이 만료되는 드문 경합은 대기열 확인으로 넘어간다
                Optional<Duration> ttl = entryTokenRepository.getTtl(userId);
                if (ttl.isPresent()) {
                    return WaitingInfo.ready(model.getToken(), ZonedDateTime.now().plus(ttl.get()));
                }
            } else {
                // Jitter 대기 중 — 노출 시각까지 남은 시간을 초 단위로 안내
                long remainingMs = Duration.between(now, model.getVisibleAt()).toMillis();
                long remainingSec = Math.max(1L, (remainingMs + 999) / 1000);
                return WaitingInfo.pendingVisibility(remainingSec);
            }
        }
        Optional<Long> position = queueRepository.getPosition(userId);
        if (position.isEmpty()) {
            return WaitingInfo.notInQueue();
        }
        return waitingInfoOf(new WaitingModel(userId, position.get()));
    }

    /**
     * 대기열 앞에서 배치 크기만큼 꺼내 입장 토큰을 발급한다. 스케줄러가 주기적으로 호출한다.
     */
    public void admitNextBatch() {
        List<Long> userIds = queueRepository.popMin(batchSize);
        for (Long userId : userIds) {
            entryTokenRepository.save(EntryTokenModel.issue(userId, jitterMaxMs), tokenTtl);
        }
    }

    /**
     * SSE 스트림을 열고 현재 상태를 즉시 push한다.
     */
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter subscribe(Long userId) {
        validateUserId(userId);
        var emitter = ssePublisher.subscribe(userId);
        ssePublisher.sendTo(userId, getPosition(userId));
        return emitter;
    }

    /**
     * 등록된 SSE 구독자 전원에게 현재 상태를 push한다. 스케줄러 배치 처리 직후 호출한다.
     */
    public void broadcastToSubscribers() {
        ssePublisher.broadcast(this::getPosition);
    }

    private WaitingInfo waitingInfoOf(WaitingModel model) {
        long estimated = waitTimeCalculator.estimateWaitSeconds(model.getPosition());
        long pollAfter = waitTimeCalculator.pollAfterSeconds(estimated);
        return WaitingInfo.waiting(model, estimated, pollAfter);
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
