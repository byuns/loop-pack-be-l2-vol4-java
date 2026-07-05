package com.loopers.view.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.view.application.ViewAggregatorService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ViewEventConsumerTest {

    private ViewAggregatorService viewAggregatorService;
    private Acknowledgment ack;
    private ViewEventConsumer consumer;

    @BeforeEach
    void setUp() {
        viewAggregatorService = mock(ViewAggregatorService.class);
        ack = mock(Acknowledgment.class);
        consumer = new ViewEventConsumer(viewAggregatorService, new ObjectMapper());
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("catalog-events", 0, 10L, "1", value);
    }

    @DisplayName("onMessage를 호출할 때,")
    @Nested
    class OnMessage {

        @DisplayName("PRODUCT_VIEWED를 수신하면, productId/occurredAt을 파싱해 집계 서비스를 호출하고 ack한다.")
        @Test
        void callsServiceAndAcks_whenProductViewed() throws Exception {
            // arrange
            String value = "{\"eventType\":\"PRODUCT_VIEWED\",\"productId\":1,\"occurredAt\":1000}";

            // act
            consumer.onMessage(record(value), ack);

            // assert
            verify(viewAggregatorService).handleProductViewed(eq("catalog-events:0:10"), eq(1L), eq(1000L));
            verify(ack).acknowledge();
        }

        @DisplayName("PRODUCT_VIEWED가 아닌 이벤트(LIKE_ADDED)는, 서비스를 호출하지 않고 ack로 흘려보낸다.")
        @Test
        void skipsAndAcks_whenNotProductViewed() throws Exception {
            // arrange
            String value = "{\"eventType\":\"LIKE_ADDED\",\"productId\":1}";

            // act
            consumer.onMessage(record(value), ack);

            // assert
            verify(viewAggregatorService, never()).handleProductViewed(any(), anyLong(), anyLong());
            verify(ack).acknowledge();
        }

        @DisplayName("집계 서비스가 예외를 던지면, 예외를 전파하고 ack하지 않는다 (ErrorHandler가 재시도/DLT 처리).")
        @Test
        void propagatesAndDoesNotAck_whenServiceThrows() {
            // arrange
            String value = "{\"eventType\":\"PRODUCT_VIEWED\",\"productId\":1,\"occurredAt\":1000}";
            doThrow(new RuntimeException("DB down"))
                .when(viewAggregatorService).handleProductViewed(any(), anyLong(), anyLong());

            // act & assert
            assertThatThrownBy(() -> consumer.onMessage(record(value), ack))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB down");
            verify(ack, never()).acknowledge();
        }
    }
}
