package com.loopers.eventhandled.domain;

public interface EventHandledRepository {
    boolean existsByEventId(String eventId);
    EventHandledModel save(EventHandledModel model);
}
