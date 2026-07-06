package com.loopers.queue.domain;

import java.time.Duration;
import java.util.Optional;

public interface EntryTokenRepository {

    /**
     * 입장 토큰을 TTL과 함께 저장. TTL이 지나면 자동 만료된다.
     */
    void save(EntryTokenModel entryToken, Duration ttl);

    /**
     * 유저에게 발급된 토큰값 조회. 발급되지 않았거나 만료되었으면 empty.
     */
    Optional<String> findByUserId(Long userId);

    /**
     * 유저의 토큰 삭제 (주문 성공 시 호출).
     */
    void deleteByUserId(Long userId);
}
