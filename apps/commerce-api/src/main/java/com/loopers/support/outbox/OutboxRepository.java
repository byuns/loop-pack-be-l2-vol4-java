package com.loopers.support.outbox;

import java.util.List;

public interface OutboxRepository {
    OutboxEvent save(OutboxEvent event);
    List<OutboxEvent> findAllByPublishedAtIsNull();
}
