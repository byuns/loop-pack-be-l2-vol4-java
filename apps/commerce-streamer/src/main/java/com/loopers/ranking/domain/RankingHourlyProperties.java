package com.loopers.ranking.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 시간 단위(1시간) 실시간 랭킹 설정. application.yml의 ranking.hourly로 바인딩된다.
 * (설계: .docs/design/10-ranking-consistency.md — 시간 단위 실시간 랭킹 H2·H3)
 *
 * @param windowMinutes 슬라이딩 윈도우 길이(분). 조회 시 합산할 최근 분 버킷 개수. (기본 60 = 지난 1시간)
 */
@ConfigurationProperties("ranking.hourly")
public record RankingHourlyProperties(int windowMinutes) {
}
