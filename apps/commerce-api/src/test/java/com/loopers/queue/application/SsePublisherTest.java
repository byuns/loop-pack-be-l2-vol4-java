package com.loopers.queue.application;

import com.loopers.queue.domain.QueueStatus;
import com.loopers.queue.domain.WaitingModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class SsePublisherTest {

    @DisplayName("SSE 구독을 관리할 때, ")
    @Nested
    class Subscribe {

        @DisplayName("subscribe하면, emitter가 발급되고 map에 저장된다.")
        @Test
        void addsEmitterToMap_whenSubscribed() {
            // arrange
            SsePublisher publisher = new SsePublisher();

            // act
            SseEmitter emitter = publisher.subscribe(1L);

            // assert
            assertAll(
                () -> assertThat(emitter).isNotNull(),
                () -> assertThat(publisher.emitterCount()).isEqualTo(1)
            );
        }

        @DisplayName("같은 유저가 다시 subscribe하면, 이전 emitter는 complete되고 새 emitter로 대체된다.")
        @Test
        void replacesPreviousEmitter_whenSubscribedTwice() {
            // arrange
            SsePublisher publisher = new SsePublisher();
            SseEmitter first = publisher.subscribe(1L);

            // act
            SseEmitter second = publisher.subscribe(1L);

            // assert
            assertAll(
                () -> assertThat(first).isNotSameAs(second),
                () -> assertThat(publisher.emitterCount()).isEqualTo(1)
            );
        }
    }

    @DisplayName("broadcast를 호출할 때, ")
    @Nested
    class Broadcast {

        @DisplayName("READY 상태의 유저에게는 ready 이벤트가 전송되고 emitter가 정리된다.")
        @Test
        void sendsReadyEventAndRemovesEmitter_whenUserIsReady() {
            // arrange
            SsePublisher publisher = new SsePublisher();
            publisher.subscribe(1L);

            // act
            publisher.broadcast(userId -> WaitingInfo.ready("token", ZonedDateTime.now().plusMinutes(5)));

            // assert — READY 이벤트 전송 후 map에서 제거됨
            assertThat(publisher.emitterCount()).isZero();
        }

        @DisplayName("WAITING 상태의 유저에게는 position 이벤트가 전송되고 emitter는 유지된다.")
        @Test
        void sendsPositionEventAndKeepsEmitter_whenUserIsWaiting() {
            // arrange
            SsePublisher publisher = new SsePublisher();
            publisher.subscribe(1L);

            // act
            publisher.broadcast(userId -> WaitingInfo.waiting(new WaitingModel(userId, 5L), 1L, 2L));

            // assert
            assertThat(publisher.emitterCount()).isEqualTo(1);
        }

        @DisplayName("NOT_IN_QUEUE 상태의 유저에게는 error 이벤트가 전송되고 emitter가 정리된다.")
        @Test
        void sendsErrorEventAndRemovesEmitter_whenUserIsNotInQueue() {
            // arrange
            SsePublisher publisher = new SsePublisher();
            publisher.subscribe(1L);

            // act
            publisher.broadcast(userId -> WaitingInfo.notInQueue());

            // assert
            assertThat(publisher.emitterCount()).isZero();
        }

        @DisplayName("여러 유저가 구독 중이면, 각자의 상태에 따라 개별 처리된다.")
        @Test
        void handlesEachSubscriberIndependently_whenMultipleUsersSubscribed() {
            // arrange
            SsePublisher publisher = new SsePublisher();
            publisher.subscribe(1L);
            publisher.subscribe(2L);
            publisher.subscribe(3L);

            // act — user1=READY (제거), user2=WAITING (유지), user3=NOT_IN_QUEUE (제거)
            publisher.broadcast(userId -> {
                if (userId == 1L) return WaitingInfo.ready("token", ZonedDateTime.now().plusMinutes(5));
                if (userId == 2L) return WaitingInfo.waiting(new WaitingModel(userId, 10L), 1L, 2L);
                return WaitingInfo.notInQueue();
            });

            // assert
            assertThat(publisher.emitterCount()).isEqualTo(1);
        }
    }
}
