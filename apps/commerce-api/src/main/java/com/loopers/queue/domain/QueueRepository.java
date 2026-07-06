package com.loopers.queue.domain;

import java.util.List;
import java.util.Optional;

public interface QueueRepository {

    /**
     * 대기열에 진입. 이미 대기 중인 유저는 기존 순번을 유지한다.
     * 상한을 넘으면 empty를 반환한다.
     * @return 대기열 내 순번 (1-based), 상한 초과 시 empty
     */
    Optional<Long> enter(Long userId, long maxSize);

    /**
     * 유저의 현재 순번 조회. 대기열에 없으면 empty.
     */
    Optional<Long> getPosition(Long userId);

    /**
     * 전체 대기 인원 조회.
     */
    Long getSize();

    /**
     * 대기열 앞에서 최대 count명을 꺼낸다 (조회 + 삭제가 원자적).
     * @return 꺼낸 유저 ID 목록 (순번 빠른 순), 비어 있으면 empty list
     */
    List<Long> popMin(long count);
}
