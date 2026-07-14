package com.loopers.ranking.application;

import com.loopers.ranking.domain.RankingKey;
import com.loopers.ranking.domain.RankingRepository;
import com.loopers.ranking.domain.RankingScoreEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Aggregator가 발행한 랭킹 점수 이벤트를 MySQL 커밋 후(AFTER_COMMIT) Redis ZSET에 반영한다.
 * 결정 1(A) — Redis는 파생 뷰라 best-effort로 처리하고, 실패는 로그만 남긴다(재구성으로 보정).
 * 일간 키와 함께 현재 분(分) 버킷에도 반영한다(시간 랭킹 H5 — write 경로 fan-out).
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class RankingEventListener {

    // 분 버킷 TTL — 윈도우(1h)에 여유를 더해 2h. 합산 후엔 필요 없어 짧게 유지한다.
    private static final Duration BUCKET_TTL = Duration.ofHours(2);

    private final RankingRepository rankingRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRankingScore(RankingScoreEvent event) {
        LocalDateTime now = LocalDateTime.now();
        try {
            rankingRepository.incrementScore(RankingKey.daily(now.toLocalDate()), event.productId(), event.score());
            rankingRepository.incrementScore(RankingKey.minute(now), event.productId(), event.score(), BUCKET_TTL);
        } catch (Exception e) {
            log.warn("[RankingEventListener] 랭킹 반영 실패(best-effort) productId={}, score={}",
                event.productId(), event.score(), e);
        }
    }
}
