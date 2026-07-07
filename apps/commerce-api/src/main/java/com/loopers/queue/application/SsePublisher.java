package com.loopers.queue.application;

import com.loopers.queue.domain.QueueStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;

/**
 * 유저별 SSE emitter를 관리하고 스케줄러 배치 후 전원에게 상태를 push한다.
 * 단일 인스턴스 가정 — 다중화 시 Redis Pub/Sub 등 별도 인프라 필요 (데모 범위 밖).
 */
@Component
public class SsePublisher {

    // 데모 규모 기본 30분 — 실서비스는 짧게 잡고 클라이언트가 재접속하는 편이 안전
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        SseEmitter previous = emitters.put(userId, emitter);
        // 같은 유저가 새 스트림을 열면 이전 것은 정리
        if (previous != null) {
            previous.complete();
        }
        emitter.onCompletion(() -> emitters.remove(userId, emitter));
        emitter.onTimeout(() -> emitters.remove(userId, emitter));
        emitter.onError(e -> emitters.remove(userId, emitter));
        return emitter;
    }

    /**
     * 등록된 각 유저에 대해 상태를 계산해 이벤트로 push한다.
     * READY / NOT_IN_QUEUE는 이벤트 전송 후 스트림을 종료한다.
     */
    public void broadcast(LongFunction<WaitingInfo> statusProvider) {
        for (Map.Entry<Long, SseEmitter> entry : emitters.entrySet()) {
            Long userId = entry.getKey();
            SseEmitter emitter = entry.getValue();
            WaitingInfo info = statusProvider.apply(userId);
            send(userId, emitter, info);
        }
    }

    /**
     * 특정 유저의 emitter에게 한 번만 상태를 push한다 (subscribe 직후 초기 상태 전송용).
     */
    public void sendTo(Long userId, WaitingInfo info) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter != null) {
            send(userId, emitter, info);
        }
    }

    int emitterCount() {
        return emitters.size();
    }

    private void send(Long userId, SseEmitter emitter, WaitingInfo info) {
        try {
            if (info.status() == QueueStatus.READY) {
                emitter.send(SseEmitter.event().name("ready").data(info));
                emitter.complete();
                emitters.remove(userId, emitter);
            } else if (info.status() == QueueStatus.WAITING) {
                emitter.send(SseEmitter.event().name("position").data(info));
            } else {
                // NOT_IN_QUEUE — 스트림 유지할 이유 없음
                emitter.send(SseEmitter.event().name("error").data(Map.of("message", "대기열에 없는 유저입니다.")));
                emitter.complete();
                emitters.remove(userId, emitter);
            }
        } catch (IOException e) {
            // 클라이언트 연결이 이미 끊긴 상태 — emitter 정리
            emitter.completeWithError(e);
            emitters.remove(userId, emitter);
        }
    }
}
