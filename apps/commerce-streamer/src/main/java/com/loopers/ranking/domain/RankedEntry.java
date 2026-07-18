package com.loopers.ranking.domain;

/**
 * ZSET 랭킹 한 건 — 상품 ID와 점수. Carry-Over 시 오늘 Top-N을 읽어 내일로 넘길 때 쓴다.
 */
public record RankedEntry(Long productId, double score) {
}
