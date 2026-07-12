package com.loopers.queue.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

public class WaitingModel {

    private final Long userId;
    private final Long position;

    public WaitingModel(Long userId, Long position) {
        if (userId == null || userId <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "userId는 양수여야 합니다.");
        }
        if (position == null || position <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "position은 양수여야 합니다.");
        }
        this.userId = userId;
        this.position = position;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getPosition() {
        return position;
    }
}
