package com.loopers.payment.interfaces.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.payment.application.SalesAggregatorService;
import com.loopers.payment.application.SalesItem;
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

    @KafkaListener(
        topics = "order-events",
        groupId = "sales-aggregator"
    )
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
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
        } catch (Exception e) {
            log.error("[PaymentEventConsumer] 처리 실패 offset={}, value={}, error={}",
                record.offset(), record.value(), e.getMessage(), e);
            // ack 안 함 → 다음 poll에 재시도. 영속 실패 시 lag 누적 → DLT 도입 필요 (후속)
        }
    }

    private List<SalesItem> parseItems(JsonNode payload) {
        List<SalesItem> items = new ArrayList<>();
        for (JsonNode item : payload.get("items")) {
            items.add(new SalesItem(item.get("productId").asLong(), item.get("quantity").asLong()));
        }
        return items;
    }
}
