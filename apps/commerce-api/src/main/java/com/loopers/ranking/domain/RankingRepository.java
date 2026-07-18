package com.loopers.ranking.domain;

import java.util.List;

public interface RankingRepository {

    /**
     * key ZSET에서 점수 내림차순으로 offset부터 limit개를 조회한다.
     */
    List<RankedEntry> findPage(String key, int offset, int limit);

    /**
     * key ZSET에서 productId의 순위(0-based 아님, 1위=1)를 반환한다. 순위권에 없으면 null.
     */
    Long findRank(String key, Long productId);
}
