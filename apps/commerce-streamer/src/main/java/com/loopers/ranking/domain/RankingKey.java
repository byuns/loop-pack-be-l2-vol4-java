package com.loopers.ranking.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 랭킹 ZSET 키 전략.
 * - 일간 키:  ranking:all:{yyyyMMdd}
 * - 분 버킷:  ranking:min:{yyyyMMddHHmm}   (시간 랭킹의 1분 양자화 봉투)
 * - 시간 롤링: ranking:hourly:current        (최근 60개 분 버킷을 사전 합산한 조회용 판)
 * (설계: .docs/design/10-ranking-consistency.md — 시간 단위 실시간 랭킹 H1~H3)
 */
public final class RankingKey {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter YYYYMMDDHHMM = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private static final String HOURLY_CURRENT = "ranking:hourly:current";

    private RankingKey() {}

    public static String daily(LocalDate date) {
        return "ranking:all:" + date.format(YYYYMMDD);
    }

    public static String minute(LocalDateTime dateTime) {
        return "ranking:min:" + dateTime.format(YYYYMMDDHHMM);
    }

    public static String hourlyCurrent() {
        return HOURLY_CURRENT;
    }
}
