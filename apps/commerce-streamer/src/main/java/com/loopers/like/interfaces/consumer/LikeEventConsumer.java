package com.loopers.like.interfaces.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.like.application.LikeAggregatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component
public class LikeEventConsumer {

    private final LikeAggregatorService likeAggregatorService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "catalog-events",
        groupId = "like-aggregator"
    )
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            // eventId = topic+partition+offset — Kafka가 unique 보장. Producer 재시작 시 중복은 향후 보강(outbox id를 header로 전달)
            String eventId = record.topic() + ":" + record.partition() + ":" + record.offset();
            JsonNode payload = objectMapper.readTree(record.value());
            String eventType = payload.get("eventType").asText();
            Long productId = payload.get("productId").asLong();

            switch (eventType) {
                case "LIKE_ADDED" -> likeAggregatorService.handleLikeAdded(eventId, productId);
                case "LIKE_CANCELLED" -> likeAggregatorService.handleLikeCancelled(eventId, productId);
                default -> log.warn("[LikeEventConsumer] Unknown eventType={}, payload={}", eventType, record.value());
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("[LikeEventConsumer] 처리 실패 offset={}, value={}, error={}",
                record.offset(), record.value(), e.getMessage(), e);
            // ack 안 함 → 다음 poll에 재시도. 영속 실패 시 lag 누적 → DLT 도입 필요 (후속)
        }
    }
}
