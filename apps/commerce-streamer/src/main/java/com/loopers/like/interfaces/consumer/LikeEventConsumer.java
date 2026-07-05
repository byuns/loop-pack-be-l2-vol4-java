package com.loopers.like.interfaces.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.like.application.LikeAggregatorService;
import com.loopers.support.kafka.DltKafkaConfig;
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

    /**
     * 시스템 예외를 던지면 DefaultErrorHandler가 1초 간격 3회 재시도 → 여전히 실패면 catalog-events.DLT로 라우팅.
     */
    @KafkaListener(
        topics = "catalog-events",
        groupId = "like-aggregator",
        containerFactory = DltKafkaConfig.DLT_LISTENER_FACTORY
    )
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
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
    }

    @KafkaListener(
        topics = DltKafkaConfig.CATALOG_EVENTS_DLT,
        groupId = "like-aggregator-dlt",
        containerFactory = DltKafkaConfig.DLT_LISTENER_FACTORY
    )
    public void onDlt(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.error("[LikeEventConsumer][DLT] 처리 불가 메시지 수신 offset={}, key={}, value={}",
            record.offset(), record.key(), record.value());
        ack.acknowledge();
    }
}
