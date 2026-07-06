package com.loopers.queue.application;

import com.loopers.queue.domain.QueueStatus;
import com.loopers.queue.domain.WaitingModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class WaitingInfoTest {

    @DisplayName("WaitingInfo를 생성할 때, ")
    @Nested
    class Create {

        @DisplayName("WAITING 상태로 생성하면, position과 estimatedWaitTime이 세팅된다.")
        @Test
        void createsWaitingInfo_whenStatusIsWaiting() {
            // arrange
            WaitingModel model = new WaitingModel(1L, 5L);
            long estimatedWaitTime = 60L;

            // act
            WaitingInfo info = WaitingInfo.waiting(model, estimatedWaitTime);

            // assert
            assertAll(
                () -> assertThat(info.status()).isEqualTo(QueueStatus.WAITING),
                () -> assertThat(info.position()).isEqualTo(5L),
                () -> assertThat(info.estimatedWaitTime()).isEqualTo(estimatedWaitTime),
                () -> assertThat(info.token()).isNull()
            );
        }

        @DisplayName("NOT_IN_QUEUE 상태로 생성하면, position/estimatedWaitTime/token 모두 null이다.")
        @Test
        void createsNotInQueueInfo_whenUserIsNotInQueue() {
            // act
            WaitingInfo info = WaitingInfo.notInQueue();

            // assert
            assertAll(
                () -> assertThat(info.status()).isEqualTo(QueueStatus.NOT_IN_QUEUE),
                () -> assertThat(info.position()).isNull(),
                () -> assertThat(info.estimatedWaitTime()).isNull(),
                () -> assertThat(info.token()).isNull()
            );
        }

        @DisplayName("READY 상태로 생성하면, token이 세팅된다.")
        @Test
        void createsReadyInfo_whenTokenIsIssued() {
            // arrange
            String token = "abc-123";

            // act
            WaitingInfo info = WaitingInfo.ready(token);

            // assert
            assertAll(
                () -> assertThat(info.status()).isEqualTo(QueueStatus.READY),
                () -> assertThat(info.token()).isEqualTo(token),
                () -> assertThat(info.position()).isNull(),
                () -> assertThat(info.estimatedWaitTime()).isNull()
            );
        }
    }
}
