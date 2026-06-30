package com.loopers.eventhandled.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;

@Entity
@Table(name = "event_handled")
public class EventHandledModel {

    @Id
    @Column(name = "event_id", length = 255, nullable = false)
    private String eventId;

    @Column(name = "handled_at", nullable = false)
    private ZonedDateTime handledAt;

    protected EventHandledModel() {}

    public EventHandledModel(String eventId) {
        this.eventId = eventId;
        this.handledAt = ZonedDateTime.now();
    }

    public String getEventId() { return eventId; }
    public ZonedDateTime getHandledAt() { return handledAt; }
}
