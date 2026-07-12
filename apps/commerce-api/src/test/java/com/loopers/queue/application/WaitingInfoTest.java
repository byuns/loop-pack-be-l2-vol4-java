package com.loopers.queue.application;

import com.loopers.queue.domain.QueueStatus;
import com.loopers.queue.domain.WaitingModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class WaitingInfoTest {

    @DisplayName("WaitingInfo를 생성할 때, ")
    @Nested
    class Create {

        @DisplayName("WAITING 상태로 생성하면, position/estimatedWaitTime/pollAfter가 세팅된다.")
        @Test
        void createsWaitingInfo_whenStatusIsWaiting() {
            // arrange
            WaitingModel model = new WaitingModel(1L, 342L);

            // act
            WaitingInfo info = WaitingInfo.waiting(model, 4L, 2L);

            // assert
            assertAll(
                () -> assertThat(info.status()).isEqualTo(QueueStatus.WAITING),
                () -> assertThat(info.position()).isEqualTo(342L),
                () -> assertThat(info.estimatedWaitTime()).isEqualTo(4L),
                () -> assertThat(info.pollAfter()).isEqualTo(2L),
                () -> assertThat(info.token()).isNull(),
                () -> assertThat(info.expiresAt()).isNull()
            );
        }

        @DisplayName("NOT_IN_QUEUE 상태로 생성하면, 모든 부가 필드가 null이다.")
        @Test
        void createsNotInQueueInfo_whenUserIsNotInQueue() {
            // act
            WaitingInfo info = WaitingInfo.notInQueue();

            // assert
            assertAll(
                () -> assertThat(info.status()).isEqualTo(QueueStatus.NOT_IN_QUEUE),
                () -> assertThat(info.position()).isNull(),
                () -> assertThat(info.estimatedWaitTime()).isNull(),
                () -> assertThat(info.pollAfter()).isNull(),
                () -> assertThat(info.token()).isNull(),
                () -> assertThat(info.expiresAt()).isNull()
            );
        }

        @DisplayName("READY 상태로 생성하면, token과 expiresAt이 세팅된다.")
        @Test
        void createsReadyInfo_whenTokenIsIssued() {
            // arrange
            String token = "abc-123";
            ZonedDateTime expiresAt = ZonedDateTime.now().plusMinutes(5);

            // act
            WaitingInfo info = WaitingInfo.ready(token, expiresAt);

            // assert
            assertAll(
                () -> assertThat(info.status()).isEqualTo(QueueStatus.READY),
                () -> assertThat(info.token()).isEqualTo(token),
                () -> assertThat(info.expiresAt()).isEqualTo(expiresAt),
                () -> assertThat(info.position()).isNull(),
                () -> assertThat(info.estimatedWaitTime()).isNull(),
                () -> assertThat(info.pollAfter()).isNull()
            );
        }
    }
}
