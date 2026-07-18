package com.loopers.ranking.application;

import com.loopers.ranking.domain.RankedEntry;
import com.loopers.ranking.domain.RankingCarryOverProperties;
import com.loopers.ranking.domain.RankingKey;
import com.loopers.ranking.domain.RankingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 콜드 스타트 완화 — 23:50에 오늘 랭킹 Top-N을 감쇠해 내일 키에 밑그림으로 깐다.
 * 자정 백지 시작으로 새벽 랭킹이 노이즈가 되는 것을 막는다.
 * (설계: .docs/design/10-ranking-consistency.md — 콜드 스타트 완화)
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class RankingCarryOverService {

    private final RankingRepository rankingRepository;
    private final RankingCarryOverProperties properties;

    @Scheduled(cron = "0 50 23 * * *", zone = "Asia/Seoul")
    public void carryOver() {
        LocalDate today = LocalDate.now();
        String todayKey = RankingKey.daily(today);
        String tomorrowKey = RankingKey.daily(today.plusDays(1));

        List<RankedEntry> top = rankingRepository.findTopScores(todayKey, properties.topN());
        if (top.isEmpty()) {
            // 첫날·무트래픽이면 넘길 밑그림이 없다 → 스킵
            return;
        }

        List<RankedEntry> decayed = top.stream()
            .map(e -> new RankedEntry(e.productId(), e.score() * properties.decay()))
            .toList();
        rankingRepository.seedScores(tomorrowKey, decayed);

        log.info("[RankingCarryOver] {} → {} Top-{} 감쇠({}) 적재", todayKey, tomorrowKey,
            top.size(), properties.decay());
    }
}
