package com.loopers.ranking.application;

import com.loopers.ranking.domain.RankingKey;
import com.loopers.ranking.domain.RankingRepository;
import com.loopers.ranking.domain.RankingScoreEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;

/**
 * Aggregator가 발행한 랭킹 점수 이벤트를 MySQL 커밋 후(AFTER_COMMIT) Redis ZSET에 반영한다.
 * 결정 1(A) — Redis는 파생 뷰라 best-effort로 처리하고, 실패는 로그만 남긴다(재구성으로 보정).
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class RankingEventListener {

    private final RankingRepository rankingRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRankingScore(RankingScoreEvent event) {
        try {
            rankingRepository.incrementScore(RankingKey.daily(LocalDate.now()), event.productId(), event.score());
        } catch (Exception e) {
            log.warn("[RankingEventListener] 랭킹 반영 실패(best-effort) productId={}, score={}",
                event.productId(), event.score(), e);
        }
    }
}
