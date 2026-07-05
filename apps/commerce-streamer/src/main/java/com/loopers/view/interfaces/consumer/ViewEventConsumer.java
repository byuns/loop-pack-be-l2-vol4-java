package com.loopers.view.interfaces.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.support.kafka.DltKafkaConfig;
import com.loopers.view.application.ViewAggregatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * catalog-events를 view-aggregator 그룹으로 구독해 조회수를 집계한다.
 * like-aggregator와 다른 그룹이라 같은 토픽을 fan-out으로 각자 받고, 여기선 PRODUCT_VIEWED만 처리한다(관심사 분리).
 * catalog-events.DLT 리스너는 LikeEventConsumer 쪽에만 두어 중복 로깅을 피한다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class ViewEventConsumer {

    private final ViewAggregatorService viewAggregatorService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "catalog-events",
        groupId = "view-aggregator",
        containerFactory = DltKafkaConfig.DLT_LISTENER_FACTORY
    )
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        String eventId = record.topic() + ":" + record.partition() + ":" + record.offset();
        JsonNode payload = objectMapper.readTree(record.value());
        String eventType = payload.get("eventType").asText();

        if ("PRODUCT_VIEWED".equals(eventType)) {
            viewAggregatorService.handleProductViewed(
                eventId,
                payload.get("productId").asLong(),
                payload.get("occurredAt").asLong()
            );
        }
        // 그 외(LIKE_ADDED 등)는 like-aggregator 관심사 → 조용히 skip하고 offset만 전진
        ack.acknowledge();
    }
}
