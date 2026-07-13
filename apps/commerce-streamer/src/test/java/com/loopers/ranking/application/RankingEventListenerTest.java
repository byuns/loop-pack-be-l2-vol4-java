package com.loopers.ranking.application;

import com.loopers.ranking.domain.RankingKey;
import com.loopers.ranking.domain.RankingRepository;
import com.loopers.ranking.domain.RankingScoreEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RankingEventListenerTest {

    private RankingRepository rankingRepository;
    private RankingEventListener listener;

    @BeforeEach
    void setUp() {
        rankingRepository = mock(RankingRepository.class);
        listener = new RankingEventListener(rankingRepository);
    }

    @DisplayName("RankingScoreEvent를 수신하면, 오늘 일간 키로 incrementScore를 호출한다.")
    @Test
    void incrementsScore_whenEventReceived() {
        // arrange
        RankingScoreEvent event = new RankingScoreEvent(1L, 0.6);

        // act
        listener.onRankingScore(event);

        // assert
        verify(rankingRepository).incrementScore(RankingKey.daily(LocalDate.now()), 1L, 0.6);
    }

    @DisplayName("Redis 반영이 실패해도(best-effort), 예외를 밖으로 전파하지 않는다.")
    @Test
    void doesNotPropagate_whenRedisFails() {
        // arrange
        doThrow(new RuntimeException("Redis down"))
            .when(rankingRepository).incrementScore(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyDouble());

        // act & assert
        assertThatCode(() -> listener.onRankingScore(new RankingScoreEvent(1L, 0.6)))
            .doesNotThrowAnyException();
    }
}
