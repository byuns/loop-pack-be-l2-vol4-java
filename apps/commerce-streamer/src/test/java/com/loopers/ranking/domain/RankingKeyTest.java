package com.loopers.ranking.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RankingKeyTest {

    @DisplayName("일간 랭킹 키는 ranking:all:{yyyyMMdd} 형식이다.")
    @Test
    void dailyKeyFormat() {
        // act
        String key = RankingKey.daily(LocalDate.of(2026, 7, 13));

        // assert
        assertThat(key).isEqualTo("ranking:all:20260713");
    }

    @DisplayName("분 버킷 키는 ranking:min:{yyyyMMddHHmm} 형식이다.")
    @Test
    void minuteKeyFormat() {
        // act
        String key = RankingKey.minute(LocalDateTime.of(2026, 7, 14, 14, 37));

        // assert
        assertThat(key).isEqualTo("ranking:min:202607141437");
    }

    @DisplayName("시간 랭킹 롤링 키는 고정 상수 ranking:hourly:current 이다.")
    @Test
    void hourlyCurrentKeyIsConstant() {
        // act
        String key = RankingKey.hourlyCurrent();

        // assert
        assertThat(key).isEqualTo("ranking:hourly:current");
    }
}
