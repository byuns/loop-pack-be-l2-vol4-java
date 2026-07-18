package com.loopers.ranking.domain;

/**
 * 랭킹 점수 반영 요청 이벤트. Aggregator가 MySQL 커밋될 트랜잭션 안에서 발행하고,
 * RankingEventListener가 AFTER_COMMIT에 Redis ZSET으로 반영한다(커밋 후 best-effort).
 */
public record RankingScoreEvent(Long productId, double score) {
}
