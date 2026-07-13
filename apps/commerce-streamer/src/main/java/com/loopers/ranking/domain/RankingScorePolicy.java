package com.loopers.ranking.domain;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 이벤트별 랭킹 점수를 계산한다. 조회·좋아요는 고정 가중치, 주문은 금액을 log10으로
 * 정규화해 한 건이 전부를 부수지 않게 한다. (설계: .docs/design/10-ranking-consistency.md)
 * 가중치는 설정(ranking.weight)에서 주입받아 재배포 없이 조절할 수 있다.
 */
@RequiredArgsConstructor
@Component
public class RankingScorePolicy {

    private final RankingWeightProperties weights;

    public double viewScore() {
        return weights.view();
    }

    public double likeAddedScore() {
        return weights.like();
    }

    public double likeCancelledScore() {
        return -weights.like();
    }

    public double orderScore(long price, long quantity) {
        // log10(1 + 금액): log(0)=-∞ 폭주와 음수를 막고, 0원이면 0점이 되게 한다
        return weights.order() * Math.log10(1 + (double) (price * quantity));
    }
}
