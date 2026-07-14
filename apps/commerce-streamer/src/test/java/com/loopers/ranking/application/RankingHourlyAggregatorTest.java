package com.loopers.ranking.application;

import com.loopers.ranking.domain.RankingHourlyProperties;
import com.loopers.ranking.domain.RankingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RankingHourlyAggregatorTest {

    private RankingRepository rankingRepository;
    private RankingHourlyAggregator aggregator;

    @BeforeEach
    void setUp() {
        rankingRepository = mock(RankingRepository.class);
        // window 60분
        aggregator = new RankingHourlyAggregator(rankingRepository, new RankingHourlyProperties(60));
    }

    @DisplayName("window=60이면 최근 60개 분 버킷 키를 hourly:current로 합산 요청한다(중복 없이).")
    @Test
    void unionsRecent60Buckets_intoHourlyCurrent() {
        // act
        aggregator.refresh();

        // assert
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(rankingRepository).unionInto(eq("ranking:hourly:current"), captor.capture(), any(Duration.class));
        List<String> buckets = captor.getValue();
        assertThat(buckets).hasSize(60);
        assertThat(buckets).allMatch(k -> k.startsWith("ranking:min:"));
        assertThat(buckets).doesNotHaveDuplicates();
    }

    @DisplayName("사전 합산 시 롤링 키에 TTL을 함께 전달한다.")
    @Test
    void passesTtl_onUnion() {
        // act
        aggregator.refresh();

        // assert
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(rankingRepository).unionInto(eq("ranking:hourly:current"), any(), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isPositive();
    }
}
