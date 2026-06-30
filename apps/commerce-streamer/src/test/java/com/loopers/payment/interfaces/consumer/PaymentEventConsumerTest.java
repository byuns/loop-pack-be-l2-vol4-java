package com.loopers.payment.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.payment.application.SalesAggregatorService;
import com.loopers.payment.application.SalesItem;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PaymentEventConsumerTest {

    private SalesAggregatorService salesAggregatorService;
    private Acknowledgment ack;
    private PaymentEventConsumer consumer;

    @BeforeEach
    void setUp() {
        salesAggregatorService = mock(SalesAggregatorService.class);
        ack = mock(Acknowledgment.class);
        consumer = new PaymentEventConsumer(salesAggregatorService, new ObjectMapper());
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("order-events", 0, 10L, "123", value);
    }

    @DisplayName("onMessage를 호출할 때,")
    @Nested
    class OnMessage {

        @DisplayName("ORDER_CONFIRMED를 수신하면, items를 파싱해 집계 서비스를 호출하고 ack한다.")
        @Test
        void callsServiceAndAcks_whenOrderConfirmed() {
            // arrange
            String value = """
                {"eventType":"ORDER_CONFIRMED","orderId":123,
                 "items":[{"productId":1,"quantity":2},{"productId":2,"quantity":1}]}
                """;

            // act
            consumer.onMessage(record(value), ack);

            // assert
            ArgumentCaptor<List<SalesItem>> captor = ArgumentCaptor.forClass(List.class);
            verify(salesAggregatorService).handleOrderConfirmed(eq("order-events:0:10"), captor.capture());
            assertThat(captor.getValue()).containsExactly(
                new SalesItem(1L, 2L),
                new SalesItem(2L, 1L)
            );
            verify(ack).acknowledge();
        }

        @DisplayName("알 수 없는 eventType이면, 집계 서비스를 호출하지 않고 ack로 메시지를 흘려보낸다.")
        @Test
        void skipsAndAcks_whenUnknownEventType() {
            // arrange
            String value = "{\"eventType\":\"ORDER_CANCELLED\",\"orderId\":123,\"items\":[]}";

            // act
            consumer.onMessage(record(value), ack);

            // assert
            verify(salesAggregatorService, never()).handleOrderConfirmed(any(), any());
            verify(ack).acknowledge();
        }

        @DisplayName("집계 서비스가 예외를 던지면, ack하지 않아 재시도를 유도한다.")
        @Test
        void doesNotAck_whenServiceThrows() {
            // arrange
            String value = "{\"eventType\":\"ORDER_CONFIRMED\",\"orderId\":123,\"items\":[{\"productId\":1,\"quantity\":2}]}";
            doThrow(new RuntimeException("DB down"))
                .when(salesAggregatorService).handleOrderConfirmed(any(), any());

            // act
            consumer.onMessage(record(value), ack);

            // assert
            verify(ack, never()).acknowledge();
        }
    }
}
