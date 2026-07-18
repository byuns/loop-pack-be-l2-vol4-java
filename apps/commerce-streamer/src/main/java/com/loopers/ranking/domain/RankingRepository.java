package com.loopers.ranking.domain;

import java.time.Duration;
import java.util.List;

public interface RankingRepository {

    /**
     * ZSET(key)의 productId 멤버 점수를 score만큼 누적하고 기본 TTL(2일)을 갱신한다. (일간 키)
     */
    void incrementScore(String key, Long productId, double score);

    /**
     * ZSET(key)의 productId 멤버 점수를 score만큼 누적하고 지정 TTL을 갱신한다. (분 버킷 — 짧은 TTL)
     */
    void incrementScore(String key, Long productId, double score, Duration ttl);

    /**
     * sourceKeys ZSET들을 합산(ZUNIONSTORE)해 destKey에 저장하고 TTL을 건다. (시간 랭킹 사전 합산)
     * 덮어쓰기라 두 번 실행돼도 결과가 같다(멱등).
     */
    void unionInto(String destKey, List<String> sourceKeys, Duration ttl);

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
