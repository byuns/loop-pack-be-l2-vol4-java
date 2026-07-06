package com.loopers.queue.application;

import com.loopers.queue.domain.QueueRepository;
import com.loopers.queue.domain.QueueStatus;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
// 스케줄러가 대기 유저를 꺼내가면 순번 검증이 깨지므로 비활성화
@TestPropertySource(properties = {"queue.max-size=5", "queue.scheduler.enabled=false"})
class QueueFacadeIntegrationTest {

    @Autowired
    private QueueFacade queueFacade;

    @Autowired
    private QueueRepository queueRepository;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    @DisplayName("대기열에 진입할 때, ")
    @Nested
    class Enter {

        @DisplayName("새 유저가 진입하면, position 1을 반환한다.")
        @Test
        void returnsPositionOne_whenNewUserEnters() {
            // act
            WaitingInfo info = queueFacade.enter(1L);

            // assert
            assertAll(
                () -> assertThat(info.status()).isEqualTo(QueueStatus.WAITING),
                () -> assertThat(info.position()).isEqualTo(1L)
            );
        }

        @DisplayName("여러 유저가 순차 진입하면, 1, 2, 3 순서대로 position이 부여된다.")
        @Test
        void assignsSequentialPositions_whenMultipleUsersEnter() {
            // act
            WaitingInfo first = queueFacade.enter(1L);
            WaitingInfo second = queueFacade.enter(2L);
            WaitingInfo third = queueFacade.enter(3L);

            // assert
            assertAll(
                () -> assertThat(first.position()).isEqualTo(1L),
                () -> assertThat(second.position()).isEqualTo(2L),
                () -> assertThat(third.position()).isEqualTo(3L)
            );
        }

        @DisplayName("동일 유저가 재진입하면, 기존 순번을 유지한다.")
        @Test
        void keepsExistingPosition_whenSameUserReenters() {
            // arrange
            queueFacade.enter(1L);
            queueFacade.enter(2L);
            queueFacade.enter(3L);

            // act
            WaitingInfo reentered = queueFacade.enter(2L);

            // assert
            assertThat(reentered.position()).isEqualTo(2L);
        }

        @DisplayName("대기열 상한(5명)을 초과하면, TOO_MANY_REQUESTS 예외가 발생한다.")
        @Test
        void throwsTooManyRequests_whenQueueIsFull() {
            // arrange
            IntStream.rangeClosed(1, 5).forEach(i -> queueFacade.enter((long) i));

            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                queueFacade.enter(6L)
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.TOO_MANY_REQUESTS);
        }

        @DisplayName("상한이 찼어도 이미 대기 중인 유저는 재진입 가능하다.")
        @Test
        void allowsReentry_whenQueueIsFullButUserAlreadyInQueue() {
            // arrange
            IntStream.rangeClosed(1, 5).forEach(i -> queueFacade.enter((long) i));

            // act
            WaitingInfo reentered = queueFacade.enter(3L);

            // assert
            assertThat(reentered.position()).isEqualTo(3L);
        }
    }

    @DisplayName("순번을 조회할 때, ")
    @Nested
    class GetPosition {

        @DisplayName("대기 중인 유저를 조회하면, status=WAITING과 정확한 position을 반환한다.")
        @Test
        void returnsWaitingStatusWithPosition_whenUserIsInQueue() {
            // arrange
            queueFacade.enter(1L);
            queueFacade.enter(2L);

            // act
            WaitingInfo info = queueFacade.getPosition(2L);

            // assert
            assertAll(
                () -> assertThat(info.status()).isEqualTo(QueueStatus.WAITING),
                () -> assertThat(info.position()).isEqualTo(2L)
            );
        }

        @DisplayName("대기열에 없는 유저를 조회하면, status=NOT_IN_QUEUE를 반환한다.")
        @Test
        void returnsNotInQueueStatus_whenUserIsNotInQueue() {
            // act
            WaitingInfo info = queueFacade.getPosition(999L);

            // assert
            assertAll(
                () -> assertThat(info.status()).isEqualTo(QueueStatus.NOT_IN_QUEUE),
                () -> assertThat(info.position()).isNull()
            );
        }
    }

    @DisplayName("전체 대기 인원을 조회할 때, ")
    @Nested
    class GetSize {

        @DisplayName("비어있는 대기열이면, 0을 반환한다.")
        @Test
        void returnsZero_whenQueueIsEmpty() {
            // act
            Long size = queueFacade.getSize();

            // assert
            assertThat(size).isEqualTo(0L);
        }

        @DisplayName("3명이 진입한 상태면, 3을 반환한다.")
        @Test
        void returnsThree_whenThreeUsersEntered() {
            // arrange
            queueFacade.enter(1L);
            queueFacade.enter(2L);
            queueFacade.enter(3L);

            // act
            Long size = queueFacade.getSize();

            // assert
            assertThat(size).isEqualTo(3L);
        }
    }

    @DisplayName("동시성 검증: ")
    @Nested
    class Concurrency {

        @DisplayName("여러 스레드가 동시에 서로 다른 유저로 진입하면, 순번이 겹치지 않고 순차 부여된다.")
        @Test
        void assignsUniquePositions_whenMultipleUsersEnterConcurrently() throws InterruptedException {
            // arrange
            int threadCount = 3;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);
            AtomicInteger[] positions = new AtomicInteger[threadCount];
            for (int i = 0; i < threadCount; i++) positions[i] = new AtomicInteger();

            // act
            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        WaitingInfo info = queueFacade.enter((long) (idx + 1));
                        positions[idx].set(info.position().intValue());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.await();
            start.countDown();
            done.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            // assert
            assertAll(
                () -> assertThat(queueFacade.getSize()).isEqualTo((long) threadCount),
                () -> assertThat(positions[0].get())
                    .isNotEqualTo(positions[1].get())
                    .isNotEqualTo(positions[2].get()),
                () -> assertThat(positions[1].get()).isNotEqualTo(positions[2].get())
            );
        }

        @DisplayName("같은 유저가 동시에 여러 번 진입해도, 단 하나의 순번만 부여된다.")
        @Test
        void assignsSinglePosition_whenSameUserEntersConcurrently() throws InterruptedException {
            // arrange
            int threadCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);

            // act
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        queueFacade.enter(1L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.await();
            start.countDown();
            done.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            // assert
            assertThat(queueFacade.getSize()).isEqualTo(1L);
        }
    }
}
