package com.loopers.ranking.domain;

import java.util.List;

public interface RankingRepository {

    /**
     * ZSET(key)의 productId 멤버 점수를 score만큼 누적하고 TTL을 갱신한다.
     */
    void incrementScore(String key, Long productId, double score);

    /**
     * ZSET(key)의 점수 상위 limit개를 내림차순으로 조회한다. (Carry-Over 소스)
     */
    List<RankedEntry> findTopScores(String key, int limit);

    /**
     * ZSET(key)에 각 멤버의 점수를 절대값(ZADD)으로 세팅하고 TTL을 건다. (Carry-Over 대상)
     * ZADD 절대 세팅이라 두 번 실행돼도 결과가 같다(멱등).
     */
    void seedScores(String key, List<RankedEntry> entries);
}
