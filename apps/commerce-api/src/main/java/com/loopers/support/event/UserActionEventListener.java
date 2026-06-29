package com.loopers.support.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class UserActionEventListener {

    private static final Logger log = LoggerFactory.getLogger(UserActionEventListener.class);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleUserAction(UserActionEvent event) {
        log.info("[USER_ACTION] userId={} action={} targetType={} targetId={}",
            event.userId(), event.action(), event.targetType(), event.targetId());
    }
}
