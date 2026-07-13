package com.loopers.ranking.interfaces;

import com.loopers.ranking.application.RankingInfo;

public class RankingV1Dto {

    public record RankingResponse(long rank, Long productId, String name, Long price, double score) {
        public static RankingResponse from(RankingInfo info) {
            return new RankingResponse(
                info.rank(),
                info.productId(),
                info.name(),
                info.price(),
                info.score()
            );
        }
    }
}
