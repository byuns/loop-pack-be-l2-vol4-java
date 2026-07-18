package com.loopers.ranking.application;

import com.loopers.ranking.domain.RankingHourlyProperties;
import com.loopers.ranking.domain.RankingKey;
import com.loopers.ranking.domain.RankingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 시간 단위(1시간) 실시간 랭킹 — 사전 합산 스케줄러(H3).
 * 1분마다 최근 windowMinutes개 분 버킷을 ZUNIONSTORE로 합산해 조회용 롤링 키에 저장한다.
 * 덮어쓰기라 여러 인스턴스가 돌아도 결과가 같다(멱등) → 별도 락 불필요.
 * (설계: .docs/design/10-ranking-consistency.md — 시간 단위 실시간 랭킹 H3)
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class RankingHourlyAggregator {

    // 롤링 키 TTL — 1분마다 갱신되므로 짧게. 스케줄러가 죽으면 낡은 판을 오래 노출하지 않고 자연 만료된다.
    private static final Duration CURRENT_TTL = Duration.ofMinutes(2);

    private final RankingRepository rankingRepository;
    private final RankingHourlyProperties properties;

    @Scheduled(fixedRate = 60_000)
    public void refresh() {
        LocalDateTime now = LocalDateTime.now();
        List<String> buckets = new ArrayList<>();
        for (int i = 0; i < properties.windowMinutes(); i++) {
            buckets.add(RankingKey.minute(now.minusMinutes(i)));
        }
        try {
            rankingRepository.unionInto(RankingKey.hourlyCurrent(), buckets, CURRENT_TTL);
        } catch (Exception e) {
            // best-effort — 다음 주기(1분 후)가 다시 합산한다.
            log.warn("[RankingHourlyAggregator] 시간 랭킹 사전 합산 실패(best-effort)", e);
        }
    }
}
