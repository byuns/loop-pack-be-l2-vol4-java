package com.loopers.queue.application;

import com.loopers.queue.domain.EntryTokenModel;
import com.loopers.queue.domain.EntryTokenRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class EntryTokenValidator {

    private final EntryTokenRepository entryTokenRepository;
    // Redis 장애 시 false로 내려 대기열 게이트를 우회하는 kill switch 겸용
    private final boolean required;

    public EntryTokenValidator(
        EntryTokenRepository entryTokenRepository,
        @Value("${queue.entry-token.required:true}") boolean required
    ) {
        this.entryTokenRepository = entryTokenRepository;
        this.required = required;
    }

    /**
     * 유저에게 발급된 토큰과 요청 토큰이 일치하는지 검증한다.
     * userId 기준으로 조회하므로 타인의 토큰으로는 통과할 수 없다.
     * 발급됐어도 Jitter visibleAt 시각이 지나지 않았으면 FORBIDDEN — 노출뿐 아니라 진입 자체를 시간축으로 흩뿌린다.
     */
    public void validate(Long userId, String entryToken) {
        if (!required) {
            return;
        }
        EntryTokenModel issued = entryTokenRepository.findByUserId(userId)
            .orElseThrow(() -> new CoreException(ErrorType.FORBIDDEN, "입장 토큰이 없거나 만료되었습니다. 대기열에 다시 진입해주세요."));
        if (!issued.getToken().equals(entryToken)) {
            throw new CoreException(ErrorType.FORBIDDEN, "유효하지 않은 입장 토큰입니다.");
        }
        if (!issued.isVisible(Instant.now())) {
            throw new CoreException(ErrorType.FORBIDDEN, "아직 입장이 준비되지 않았습니다.");
        }
    }

    public void consume(Long userId) {
        entryTokenRepository.deleteByUserId(userId);
    }
}
