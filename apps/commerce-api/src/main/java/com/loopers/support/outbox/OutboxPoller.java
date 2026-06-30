package com.loopers.support.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
@Component
public class OutboxPoller {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<Object, Object> kafkaTemplate;

    @Scheduled(fixedDelay = 5000)
    public void poll() {
        List<OutboxEvent> events = outboxRepository.findAllByPublishedAtIsNull();
        for (OutboxEvent event : events) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload()).get(10, TimeUnit.SECONDS);
                markAsPublished(event);
            } catch (Exception e) {
                log.error("[OutboxPoller] Kafka 발행 실패 - eventId={}, topic={}, error={}", event.getId(), event.getTopic(), e.getMessage());
            }
        }
    }

    @Transactional
    public void markAsPublished(OutboxEvent event) {
        event.markAsPublished();
        outboxRepository.save(event);
    }
}
