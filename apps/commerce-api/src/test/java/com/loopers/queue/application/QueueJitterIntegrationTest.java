package com.loopers.queue.application;

import com.loopers.queue.domain.EntryTokenRepository;
import com.loopers.queue.domain.QueueStatus;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

// jitter.max-ms=100 — 짧아서 테스트 시간 부담이 작지만, Jitter 유무를 확실히 관찰할 수 있는 값
@SpringBootTest
@TestPropertySource(properties = {
    "queue.scheduler.enabled=false",
    "queue.scheduler.batch-size=10",
    "queue.token.ttl-seconds=300",
    "queue.jitter.max-ms=100"
})
class QueueJitterIntegrationTest {

    @Autowired
    private QueueFacade queueFacade;

    @Autowired
    private EntryTokenRepository entryTokenRepository;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    @DisplayName("Jitter가 활성화된 상태에서 순번을 조회할 때, ")
    @Nested
    class GetPositionWithJitter {

        @DisplayName("배치 직후 조회하면, visibleAt이 아직 지나지 않아 WAITING(position=0)을 반환할 수 있다.")
        @Test
        void returnsWaitingOrReady_dependingOnJitter() {
            // arrange
            queueFacade.enter(1L);

            // act
            queueFacade.admitNextBatch();
            WaitingInfo info = queueFacade.getPosition(1L);

            // assert — jitter 범위가 [0,100]이므로 즉시 READY거나 WAITING일 수 있음
            // 배치 직후 조회는 WAITING이 대다수지만 delay=0이 뽑히면 READY도 가능하므로 둘 다 허용
            assertThat(info.status()).isIn(QueueStatus.WAITING, QueueStatus.READY);
            if (info.status() == QueueStatus.WAITING) {
                assertAll(
                    () -> assertThat(info.position()).isZero(),
                    () -> assertThat(info.estimatedWaitTime()).isNotNull(),
                    () -> assertThat(info.token()).isNull()
                );
            } else {
                assertThat(info.token()).isNotNull();
            }
        }

        @DisplayName("Jitter 상한(100ms)보다 충분히 오래 대기하면, READY와 토큰이 반환된다.")
        @Test
        void returnsReady_afterVisibleAtPasses() throws InterruptedException {
            // arrange
            queueFacade.enter(1L);
            queueFacade.admitNextBatch();

            // act — Jitter 상한(100ms)의 1.5배로 확실히 시각을 지나가게 한다
            Thread.sleep(150);
            WaitingInfo info = queueFacade.getPosition(1L);

            // assert
            assertAll(
                () -> assertThat(info.status()).isEqualTo(QueueStatus.READY),
                () -> assertThat(info.token()).isNotNull(),
                () -> assertThat(info.token()).isEqualTo(entryTokenRepository.findByUserId(1L).orElseThrow().getToken())
            );
        }
    }
}
