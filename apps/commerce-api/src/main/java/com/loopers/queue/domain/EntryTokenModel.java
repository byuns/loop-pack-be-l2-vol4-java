package com.loopers.queue.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.util.UUID;

public class EntryTokenModel {

    private final Long userId;
    private final String token;

    public EntryTokenModel(Long userId, String token) {
        if (userId == null || userId <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "userId는 양수여야 합니다.");
        }
        if (token == null || token.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "token은 비어있을 수 없습니다.");
        }
        this.userId = userId;
        this.token = token;
    }

    public static EntryTokenModel issue(Long userId) {
        return new EntryTokenModel(userId, UUID.randomUUID().toString());
    }

    public Long getUserId() {
        return userId;
    }

    public String getToken() {
        return token;
    }
}
