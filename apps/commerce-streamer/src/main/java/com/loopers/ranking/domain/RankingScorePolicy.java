package com.loopers.ranking.domain;

import org.springframework.stereotype.Component;

/**
 * 이벤트별 랭킹 점수를 계산한다. 조회·좋아요는 고정 가중치, 주문은 금액을 log10으로
 * 정규화해 한 건이 전부를 부수지 않게 한다. (설계: .docs/design/10-ranking-consistency.md)
 */
@Component
public class RankingScorePolicy {

    private static final double VIEW_WEIGHT = 0.1;
    private static final double LIKE_WEIGHT = 0.2;
    private static final double ORDER_WEIGHT = 0.6;

    public double viewScore() {
        return VIEW_WEIGHT;
    }

    public double likeAddedScore() {
        return LIKE_WEIGHT;
    }

    public double likeCancelledScore() {
        return -LIKE_WEIGHT;
    }

    public double orderScore(long price, long quantity) {
        // log10(1 + 금액): log(0)=-∞ 폭주와 음수를 막고, 0원이면 0점이 되게 한다
        return ORDER_WEIGHT * Math.log10(1 + (double) (price * quantity));
    }
}
