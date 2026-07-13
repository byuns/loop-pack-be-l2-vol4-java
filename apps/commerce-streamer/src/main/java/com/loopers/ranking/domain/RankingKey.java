package com.loopers.ranking.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 랭킹 ZSET 키 전략. 일간 키: ranking:all:{yyyyMMdd}
 */
public final class RankingKey {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private RankingKey() {}

    public static String daily(LocalDate date) {
        return "ranking:all:" + date.format(YYYYMMDD);
    }
}
