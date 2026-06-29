package com.loopers.support.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserActionEventListenerTest {

    private UserActionEventListener userActionEventListener;

    @BeforeEach
    void setUp() {
        userActionEventListener = new UserActionEventListener();
    }

    @DisplayName("UserActionEvent를 수신할 때, 예외 없이 처리한다.")
    @Test
    void handlesUserActionEvent_withoutException() {
        // arrange
        UserActionEvent event = new UserActionEvent(1L, "ORDER", "order", 42L);

        // act & assert
        userActionEventListener.handleUserAction(event);
    }
}
