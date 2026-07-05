package com.loopers.eventhandled.infrastructure;

import com.loopers.eventhandled.domain.EventHandledModel;
import com.loopers.eventhandled.domain.EventHandledRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class EventHandledRepositoryImpl implements EventHandledRepository {

    private final EventHandledJpaRepository jpa;

    @Override
    public boolean existsByEventId(String eventId) {
        return jpa.existsByEventId(eventId);
    }

    @Override
    public EventHandledModel save(EventHandledModel model) {
        return jpa.save(model);
    }
}
