package com.loopers.coupon.interfaces.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.coupon.application.CouponIssueService;
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
public class CouponIssueConsumer {

    private final CouponIssueService couponIssueService;
    private final ObjectMapper objectMapper;

    /**
     * 정상 처리: 비즈니스 실패(수량 소진/중복)는 서비스가 FAILED로 마킹한 뒤 정상 반환 → ack.
     * 시스템 실패(DB 예외 등): 예외를 그대로 던지면 DefaultErrorHandler가 1초 간격 3회 재시도 →
     * 여전히 실패면 DeadLetterPublishingRecoverer가 coupon-issue-requests.DLT로 라우팅한다.
     */
    @KafkaListener(
        topics = "coupon-issue-requests",
        groupId = "coupon-issuer",
        containerFactory = DltKafkaConfig.DLT_LISTENER_FACTORY
    )
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        JsonNode payload = objectMapper.readTree(record.value());
        String eventType = payload.get("eventType").asText();

        if ("COUPON_ISSUE_REQUESTED".equals(eventType)) {
            long requestId = payload.get("requestId").asLong();
            long couponId = payload.get("couponId").asLong();
            long userId = payload.get("userId").asLong();
            couponIssueService.handleCouponIssueRequest(requestId, couponId, userId);
        } else {
            log.warn("[CouponIssueConsumer] Unknown eventType={}, payload={}", eventType, record.value());
        }

        ack.acknowledge();
    }

    @KafkaListener(
        topics = DltKafkaConfig.COUPON_ISSUE_REQUESTS_DLT,
        groupId = "coupon-issuer-dlt",
        containerFactory = DltKafkaConfig.DLT_LISTENER_FACTORY
    )
    public void onDlt(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.error("[CouponIssueConsumer][DLT] 처리 불가 메시지 수신 offset={}, key={}, value={}",
            record.offset(), record.key(), record.value());
        ack.acknowledge();
    }
}
