package com.loopers.queue.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "queue.scheduler.enabled", havingValue = "true")
public class QueueScheduler {

    private final QueueFacade queueFacade;

    public QueueScheduler(QueueFacade queueFacade) {
        this.queueFacade = queueFacade;
    }

    @Scheduled(fixedDelayString = "${queue.scheduler.interval-ms:100}")
    public void admit() {
        queueFacade.admitNextBatch();
        queueFacade.broadcastToSubscribers();
    }
}
