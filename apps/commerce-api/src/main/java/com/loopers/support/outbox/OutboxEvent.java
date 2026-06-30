package com.loopers.support.outbox;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent extends BaseEntity {

    @Column(nullable = false)
    private String aggregateId;

    @Column(nullable = false)
    private String topic;

    @Column(nullable = false)
    private String eventType;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;

    private ZonedDateTime publishedAt;

    protected OutboxEvent() {}

    public static OutboxEvent of(String topic, String eventType, String aggregateId, String payload) {
        OutboxEvent event = new OutboxEvent();
        event.topic = topic;
        event.eventType = eventType;
        event.aggregateId = aggregateId;
        event.payload = payload;
        return event;
    }

    public void markAsPublished() {
        this.publishedAt = ZonedDateTime.now();
    }

    public String getAggregateId() { return aggregateId; }
    public String getTopic() { return topic; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public ZonedDateTime getPublishedAt() { return publishedAt; }
}
