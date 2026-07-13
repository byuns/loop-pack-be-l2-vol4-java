package com.loopers.ranking.domain;

/**
 * ZSET 랭킹 한 건 — 상품 ID와 누적 점수. 점수 내림차순(랭킹 순)으로 조회된다.
 */
public record RankedEntry(Long productId, double score) {
}
