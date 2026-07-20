package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.RankAggregationJobConfig;
import com.loopers.ranking.domain.RankPeriod;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 집계 적재 전에 대상 MV를 비운다. MV를 매 실행 통째로 교체(overwrite)해 멱등하게 만든다.
 */
@StepScope
@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = RankAggregationJobConfig.JOB_NAME)
@Component
public class RankClearTasklet implements Tasklet {

    @PersistenceContext
    private EntityManager entityManager;

    private final RankPeriod period;

    public RankClearTasklet(@Value("#{jobParameters['period']}") String period) {
        this.period = RankPeriod.from(period);
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        entityManager.createQuery("DELETE FROM " + period.targetEntityName()).executeUpdate();
        return RepeatStatus.FINISHED;
    }
}
