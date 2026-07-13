package com.loopers.ranking.domain;

public interface RankingRepository {

    /**
     * ZSET(key)의 productId 멤버 점수를 score만큼 누적하고 TTL을 갱신한다.
     */
    void incrementScore(String key, Long productId, double score);
}
