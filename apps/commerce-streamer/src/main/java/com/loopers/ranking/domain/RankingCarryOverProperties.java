package com.loopers.ranking.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 콜드 스타트 완화(Score Carry-Over) 설정. application.yml의 ranking.carryover로 바인딩된다.
 * (설계: .docs/design/10-ranking-consistency.md — 콜드 스타트 완화)
 *
 * @param decay 내일로 넘길 때 곱하는 감쇠계수(0~1). 밑그림만 남기고 오늘 이벤트가 주도권을 갖게 한다.
 * @param topN  넘길 상위 상품 개수. 새벽엔 상위권만 의미 있어 전체가 아닌 Top-N만 넘긴다.
 */
@ConfigurationProperties("ranking.carryover")
public record RankingCarryOverProperties(double decay, int topN) {
}
