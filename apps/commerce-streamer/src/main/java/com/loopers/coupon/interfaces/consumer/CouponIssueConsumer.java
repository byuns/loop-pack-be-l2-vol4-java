package com.loopers.coupon.interfaces.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.coupon.application.CouponIssueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component
public class CouponIssueConsumer {

    private final CouponIssueService couponIssueService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "coupon-issue-requests",
        groupId = "coupon-issuer"
    )
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            JsonNode payload = objectMapper.readTree(record.value());
            String eventType = payload.get("eventType").asText();

            if ("COUPON_ISSUE_REQUESTED".equals(eventType)) {
                long requestId = payload.get("requestId").asLong();
                long couponId = payload.get("couponId").asLong();
                long userId = payload.get("userId").asLong();
                // 비즈니스 실패(중복/수량소진)는 서비스 내에서 FAILED 처리 후 정상 반환 → ack
                // 시스템 실패(DB 예외 등)는 예외 전파 → catch로 빠져 ack 안 함 → Kafka 재시도
                couponIssueService.handleCouponIssueRequest(requestId, couponId, userId);
            } else {
                log.warn("[CouponIssueConsumer] Unknown eventType={}, payload={}", eventType, record.value());
            }

            ack.acknowledge();
        } catch (Exception e) {
            log.error("[CouponIssueConsumer] 처리 실패 offset={}, value={}, error={}",
                record.offset(), record.value(), e.getMessage(), e);
            // ack 안 함 → 다음 poll에 재시도
        }
    }

    @KafkaListener(
        topics = "coupon-issue-requests.DLT",
        groupId = "coupon-issuer-dlt"
    )
    public void onDlt(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.error("[CouponIssueConsumer][DLT] 처리 불가 메시지 수신 offset={}, value={}",
            record.offset(), record.value());
        ack.acknowledge();
    }
}
