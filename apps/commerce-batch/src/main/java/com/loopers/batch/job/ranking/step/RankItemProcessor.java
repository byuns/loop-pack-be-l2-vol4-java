package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.ProductMetricRow;
import com.loopers.batch.job.ranking.RankAggregationJobConfig;
import com.loopers.ranking.domain.ProductRankModel;
import com.loopers.ranking.domain.RankPeriod;
import com.loopers.ranking.domain.RankingScorePolicy;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reader가 점수순(내림차순)으로 흘려준 행에 순차 rank를 부여하고, 정책으로 저장할 점수를 계산해
 * 기간(period)에 맞는 MV 엔티티로 변환한다.
 * StepScope라 Step 실행마다 새 인스턴스가 만들어져 rank 카운터가 초기화된다.
 */
@StepScope
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = RankAggregationJobConfig.JOB_NAME)
@Component
public class RankItemProcessor implements ItemProcessor<ProductMetricRow, ProductRankModel> {

    private final RankingScorePolicy scorePolicy;
    private final RankPeriod period;
    private final AtomicInteger rankCounter = new AtomicInteger(0);

    public RankItemProcessor(
        RankingScorePolicy scorePolicy,
        @Value("#{jobParameters['period']}") String period
    ) {
        this.scorePolicy = scorePolicy;
        this.period = RankPeriod.from(period);
    }

    @Override
    public ProductRankModel process(ProductMetricRow row) {
        double score = scorePolicy.score(row.viewCount(), row.likeCount(), row.salesCount());
        int rank = rankCounter.incrementAndGet();
        return period.createRank(row.productId(), rank, score);
    }
}
