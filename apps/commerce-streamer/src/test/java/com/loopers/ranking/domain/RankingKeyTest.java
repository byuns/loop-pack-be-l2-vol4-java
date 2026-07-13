package com.loopers.ranking.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

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
}
