package com.loopers.payment.interfaces.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.payment.application.SalesAggregatorService;
import com.loopers.payment.application.SalesItem;
import com.loopers.support.kafka.DltKafkaConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
public class PaymentEventConsumer {

    private final SalesAggregatorService salesAggregatorService;
    private final ObjectMapper objectMapper;

    /**
     * 시스템 예외를 던지면 DefaultErrorHandler가 1초 간격 3회 재시도 → 여전히 실패면 order-events.DLT로 라우팅.
     */
    @KafkaListener(
        topics = "order-events",
        groupId = "sales-aggregator",
        containerFactory = DltKafkaConfig.DLT_LISTENER_FACTORY
    )
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        JsonNode payload = objectMapper.readTree(record.value());
        String eventType = payload.get("eventType").asText();

        switch (eventType) {
            // 멱등 키는 비즈니스 키(orderId) 사용 — ORDER_CONFIRMED는 주문당 1회뿐이라
            // outbox 재발행·리밸런싱·리플레이로 Kafka 좌표가 바뀌어도 중복을 정확히 감지한다.
            case "ORDER_CONFIRMED" -> salesAggregatorService.handleOrderConfirmed(
                "order:" + payload.get("orderId").asLong(), parseItems(payload));
            default -> log.warn("[PaymentEventConsumer] Unknown eventType={}, payload={}", eventType, record.value());
        }
        ack.acknowledge();
    }

    @KafkaListener(
        topics = DltKafkaConfig.ORDER_EVENTS_DLT,
        groupId = "sales-aggregator-dlt",
        containerFactory = DltKafkaConfig.DLT_LISTENER_FACTORY
    )
    public void onDlt(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.error("[PaymentEventConsumer][DLT] 처리 불가 메시지 수신 offset={}, key={}, value={}",
            record.offset(), record.key(), record.value());
        ack.acknowledge();
    }

    private List<SalesItem> parseItems(JsonNode payload) {
        List<SalesItem> items = new ArrayList<>();
        for (JsonNode item : payload.get("items")) {
            items.add(new SalesItem(item.get("productId").asLong(), item.get("quantity").asLong()));
        }
        return items;
    }
}
