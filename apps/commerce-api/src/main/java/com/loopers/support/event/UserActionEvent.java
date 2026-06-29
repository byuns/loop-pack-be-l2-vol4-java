package com.loopers.support.event;

public record UserActionEvent(Long userId, String action, String targetType, Long targetId) {
}
