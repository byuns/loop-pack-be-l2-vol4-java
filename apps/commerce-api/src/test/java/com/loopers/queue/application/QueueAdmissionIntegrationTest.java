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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.ZonedDateTime;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
@TestPropertySource(properties = {
    "queue.scheduler.enabled=false",
    "queue.scheduler.batch-size=10",
    "queue.token.ttl-seconds=300"
})
class QueueAdmissionIntegrationTest {

    @Autowired
    private QueueFacade queueFacade;

    @Autowired
    private EntryTokenRepository entryTokenRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    @DisplayName("배치 입장 처리를 실행할 때, ")
    @Nested
    class AdmitNextBatch {

        @DisplayName("대기열에 15명이 있으면, 앞 10명만 토큰이 발급되고 5명이 잔류한다.")
        @Test
        void admitsFirstTenUsers_whenFifteenUsersAreWaiting() {
            // arrange
            IntStream.rangeClosed(1, 15).forEach(i -> queueFacade.enter((long) i));

            // act
            queueFacade.admitNextBatch();

            // assert
            assertAll(
                () -> assertThat(queueFacade.getSize()).isEqualTo(5L),
                () -> IntStream.rangeClosed(1, 10).forEach(i ->
                    assertThat(entryTokenRepository.findByUserId((long) i)).isPresent()
                ),
                () -> IntStream.rangeClosed(11, 15).forEach(i ->
                    assertThat(entryTokenRepository.findByUserId((long) i)).isEmpty()
                )
            );
        }

        @DisplayName("잔류 유저의 순번은 앞당겨진다 — 11번째 유저가 1번이 된다.")
        @Test
        void advancesRemainingPositions_whenFrontUsersAreAdmitted() {
            // arrange
            IntStream.rangeClosed(1, 11).forEach(i -> queueFacade.enter((long) i));

            // act
            queueFacade.admitNextBatch();

            // assert
            WaitingInfo info = queueFacade.getPosition(11L);
            assertAll(
                () -> assertThat(info.status()).isEqualTo(QueueStatus.WAITING),
                () -> assertThat(info.position()).isEqualTo(1L)
            );
        }

        @DisplayName("대기 인원이 배치 크기보다 적으면, 전원에게 토큰이 발급된다.")
        @Test
        void admitsAllUsers_whenQueueIsSmallerThanBatchSize() {
            // arrange
            IntStream.rangeClosed(1, 3).forEach(i -> queueFacade.enter((long) i));

            // act
            queueFacade.admitNextBatch();

            // assert
            assertAll(
                () -> assertThat(queueFacade.getSize()).isEqualTo(0L),
                () -> IntStream.rangeClosed(1, 3).forEach(i ->
                    assertThat(entryTokenRepository.findByUserId((long) i)).isPresent()
                )
            );
        }

        @DisplayName("대기열이 비어 있으면, 아무 일도 일어나지 않는다.")
        @Test
        void doesNothing_whenQueueIsEmpty() {
            // act
            queueFacade.admitNextBatch();

            // assert
            assertThat(queueFacade.getSize()).isEqualTo(0L);
        }

        @DisplayName("발급된 토큰에는 TTL이 설정된다.")
        @Test
        void setsTtl_whenTokenIsIssued() {
            // arrange
            queueFacade.enter(1L);

            // act
            queueFacade.admitNextBatch();

            // assert
            Long expire = redisTemplate.getExpire("queue:token:1");
            assertThat(expire).isPositive().isLessThanOrEqualTo(300L);
        }
    }

    @DisplayName("토큰 발급 후 순번을 조회할 때, ")
    @Nested
    class GetPositionAfterAdmission {

        @DisplayName("토큰이 발급된 유저를 조회하면, status=READY와 발급된 토큰을 반환한다.")
        @Test
        void returnsReadyWithToken_whenTokenIsIssued() {
            // arrange
            queueFacade.enter(1L);
            queueFacade.admitNextBatch();

            // act
            WaitingInfo info = queueFacade.getPosition(1L);

            // assert
            assertAll(
                () -> assertThat(info.status()).isEqualTo(QueueStatus.READY),
                () -> assertThat(info.token()).isEqualTo(entryTokenRepository.findByUserId(1L).orElseThrow().getToken()),
                () -> assertThat(info.position()).isNull()
            );
        }

        @DisplayName("아직 대기 중인 유저를 조회하면, 기존처럼 status=WAITING을 반환한다.")
        @Test
        void returnsWaiting_whenUserIsStillInQueue() {
            // arrange
            IntStream.rangeClosed(1, 11).forEach(i -> queueFacade.enter((long) i));
            queueFacade.admitNextBatch();

            // act
            WaitingInfo info = queueFacade.getPosition(11L);

            // assert
            assertAll(
                () -> assertThat(info.status()).isEqualTo(QueueStatus.WAITING),
                () -> assertThat(info.token()).isNull()
            );
        }

        @DisplayName("대기열에도 없고 토큰도 없는 유저를 조회하면, status=NOT_IN_QUEUE를 반환하고 부가 필드는 모두 null이다.")
        @Test
        void returnsNotInQueue_whenUserHasNoTokenAndNotWaiting() {
            // act
            WaitingInfo info = queueFacade.getPosition(999L);

            // assert
            assertAll(
                () -> assertThat(info.status()).isEqualTo(QueueStatus.NOT_IN_QUEUE),
                () -> assertThat(info.estimatedWaitTime()).isNull(),
                () -> assertThat(info.pollAfter()).isNull(),
                () -> assertThat(info.expiresAt()).isNull()
            );
        }
    }

    @DisplayName("예상 대기 시간을 조회할 때, ")
    @Nested
    class EstimatedWaitTime {

        @DisplayName("342번째 대기 유저를 조회하면, 예상 4초와 pollAfter 2초를 반환한다.")
        @Test
        void returnsEstimateAndPollAfter_whenUserIsWaiting() {
            // arrange — batch 10 × 초당 10회 = 초당 100명 처리 기준
            IntStream.rangeClosed(1, 342).forEach(i -> queueFacade.enter((long) i));

            // act
            WaitingInfo info = queueFacade.getPosition(342L);

            // assert
            assertAll(
                () -> assertThat(info.status()).isEqualTo(QueueStatus.WAITING),
                () -> assertThat(info.estimatedWaitTime()).isEqualTo(4L),
                () -> assertThat(info.pollAfter()).isEqualTo(2L)
            );
        }

        @DisplayName("토큰이 발급된 유저를 조회하면, expiresAt이 현재~5분 사이의 미래 시각이다.")
        @Test
        void returnsFutureExpiresAt_whenTokenIsIssued() {
            // arrange
            queueFacade.enter(1L);
            queueFacade.admitNextBatch();
            ZonedDateTime now = ZonedDateTime.now();

            // act
            WaitingInfo info = queueFacade.getPosition(1L);

            // assert
            assertAll(
                () -> assertThat(info.status()).isEqualTo(QueueStatus.READY),
                () -> assertThat(info.expiresAt()).isAfter(now),
                () -> assertThat(info.expiresAt()).isBeforeOrEqualTo(now.plusMinutes(5).plusSeconds(1))
            );
        }
    }
}
