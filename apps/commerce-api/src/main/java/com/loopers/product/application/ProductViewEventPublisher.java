package com.loopers.product.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.product.domain.event.ProductViewedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 조회 이벤트를 catalog-events로 직접 발행한다 (fire-and-forget).
 * 조회는 유실 허용이라 Outbox를 거치지 않고, At-Least-Once 대신 발행 실패 시 그냥 흘려보낸다.
 * AFTER_COMMIT + @Async — 커밋 후 별도 스레드에서 발행해 요청 경로를 막지 않는다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class ProductViewEventPublisher {

    private static final String TOPIC = "catalog-events";

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(ProductViewedEvent event) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventType", "PRODUCT_VIEWED");
            payload.put("productId", event.productId());
            payload.put("occurredAt", event.occurredAt().toEpochMilli());
            kafkaTemplate.send(TOPIC, String.valueOf(event.productId()), objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            log.error("[ProductViewEventPublisher] PRODUCT_VIEWED 직렬화 실패 productId={}", event.productId(), e);
        } catch (Exception e) {
            // 조회는 유실 허용 — 발행 실패해도 요청에 영향 없음
            log.warn("[ProductViewEventPublisher] PRODUCT_VIEWED 발행 실패 productId={}, error={}", event.productId(), e.getMessage());
        }
    }
}
