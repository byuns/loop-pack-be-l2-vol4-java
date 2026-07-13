package com.loopers.ranking.application;

import com.loopers.ranking.domain.RankedEntry;
import com.loopers.ranking.domain.RankingCarryOverProperties;
import com.loopers.ranking.domain.RankingKey;
import com.loopers.ranking.domain.RankingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RankingCarryOverServiceTest {

    private static final String TOMORROW_KEY = RankingKey.daily(LocalDate.now().plusDays(1));

    private RankingRepository rankingRepository;
    private RankingCarryOverService service;

    @BeforeEach
    void setUp() {
        rankingRepository = mock(RankingRepository.class);
        // decay 0.1, topN 3
        service = new RankingCarryOverService(rankingRepository, new RankingCarryOverProperties(0.1, 3));
    }

    @DisplayName("오늘 Top-N 점수를 감쇠(×decay)해 내일 키에 seed 한다.")
    @Test
    void seedsDecayedTopN_toTomorrowKey() {
        // arrange — 오늘 키에 상위 점수 2건
        String todayKey = RankingKey.daily(LocalDate.now());
        when(rankingRepository.findTopScores(todayKey, 3))
            .thenReturn(List.of(new RankedEntry(1L, 100.0), new RankedEntry(2L, 50.0)));

        // act
        service.carryOver();

        // assert — 내일 키에 점수 × 0.1로 seed
        ArgumentCaptor<List<RankedEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(rankingRepository).seedScores(eq(TOMORROW_KEY), captor.capture());
        List<RankedEntry> seeded = captor.getValue();
        assertThat(seeded).containsExactly(new RankedEntry(1L, 10.0), new RankedEntry(2L, 5.0));
    }

    @DisplayName("오늘 키가 비어 있으면, 내일 키에 아무것도 seed 하지 않는다.")
    @Test
    void doesNotSeed_whenTodayIsEmpty() {
        // arrange
        when(rankingRepository.findTopScores(anyString(), anyInt())).thenReturn(List.of());

        // act
        service.carryOver();

        // assert
        verify(rankingRepository, never()).seedScores(anyString(), anyList());
    }
}
