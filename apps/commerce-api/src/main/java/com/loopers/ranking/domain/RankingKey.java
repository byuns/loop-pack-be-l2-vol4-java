package com.loopers.ranking.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 랭킹 ZSET 키 전략. 일간 키: ranking:all:{yyyyMMdd}
 * (commerce-streamer가 적재하는 키와 동일 포맷 — 앱이 분리돼 있어 각자 보유한다)
 */
public final class RankingKey {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private RankingKey() {}

    public static String daily(LocalDate date) {
        return "ranking:all:" + date.format(YYYYMMDD);
    }
}
