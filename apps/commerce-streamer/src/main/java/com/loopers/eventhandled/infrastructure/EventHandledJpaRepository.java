package com.loopers.eventhandled.infrastructure;

import com.loopers.eventhandled.domain.EventHandledModel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventHandledJpaRepository extends JpaRepository<EventHandledModel, String> {
    boolean existsByEventId(String eventId);
}
